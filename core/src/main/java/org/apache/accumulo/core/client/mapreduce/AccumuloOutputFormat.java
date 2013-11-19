/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.accumulo.core.client.mapreduce;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.BatchWriterConfig;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.client.Instance;
import org.apache.accumulo.core.client.MultiTableBatchWriter;
import org.apache.accumulo.core.client.MutationsRejectedException;
import org.apache.accumulo.core.client.TableExistsException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.ZooKeeperInstance;
import org.apache.accumulo.core.client.mapreduce.lib.util.OutputConfigurator;
import org.apache.accumulo.core.client.mock.MockInstance;
import org.apache.accumulo.core.client.security.SecurityErrorCode;
import org.apache.accumulo.core.client.security.tokens.AuthenticationToken;
import org.apache.accumulo.core.client.security.tokens.AuthenticationToken.AuthenticationTokenSerializer;
import org.apache.accumulo.core.conf.ClientConfiguration;
import org.apache.accumulo.core.data.ColumnUpdate;
import org.apache.accumulo.core.data.KeyExtent;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.security.ColumnVisibility;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

/**
 * This class allows MapReduce jobs to use Accumulo as the sink for data. This {@link OutputFormat} accepts keys and values of type {@link Text} (for a table
 * name) and {@link Mutation} from the Map and Reduce functions.
 * 
 * The user must specify the following via static configurator methods:
 * 
 * <ul>
 * <li>{@link AccumuloOutputFormat#setConnectorInfo(Job, String, AuthenticationToken)}
 * <li>{@link AccumuloOutputFormat#setConnectorInfo(Job, String, String)}
 * <li>{@link AccumuloOutputFormat#setZooKeeperInstance(Job, ClientConfiguration)} OR {@link AccumuloOutputFormat#setMockInstance(Job, String)}
 * </ul>
 * 
 * Other static methods are optional.
 */
public class AccumuloOutputFormat extends OutputFormat<Text,Mutation> {

  private static final Class<?> CLASS = AccumuloOutputFormat.class;
  protected static final Logger log = Logger.getLogger(CLASS);

  /**
   * Sets the connector information needed to communicate with Accumulo in this job.
   * 
   * <p>
   * <b>WARNING:</b> The serialized token is stored in the configuration and shared with all MapReduce tasks. It is BASE64 encoded to provide a charset safe
   * conversion to a string, and is not intended to be secure.
   * 
   * @param job
   *          the Hadoop job instance to be configured
   * @param principal
   *          a valid Accumulo user name (user must have Table.CREATE permission if {@link #setCreateTables(Job, boolean)} is set to true)
   * @param token
   *          the user's password
   * @throws AccumuloSecurityException
   * @since 1.5.0
   */
  public static void setConnectorInfo(Job job, String principal, AuthenticationToken token) throws AccumuloSecurityException {
    OutputConfigurator.setConnectorInfo(CLASS, job.getConfiguration(), principal, token);
  }

  /**
   * Sets the connector information needed to communicate with Accumulo in this job.
   * 
   * <p>
   * Stores the password in a file in HDFS and pulls that into the Distributed Cache in an attempt to be more secure than storing it in the Configuration.
   * 
   * @param job
   *          the Hadoop job instance to be configured
   * @param principal
   *          a valid Accumulo user name (user must have Table.CREATE permission if {@link #setCreateTables(Job, boolean)} is set to true)
   * @param tokenFile
   *          the path to the token file
   * @throws AccumuloSecurityException
   * @since 1.6.0
   */
  public static void setConnectorInfo(Job job, String principal, String tokenFile) throws AccumuloSecurityException {
    OutputConfigurator.setConnectorInfo(CLASS, job.getConfiguration(), principal, tokenFile);
  }

  /**
   * Determines if the connector has been configured.
   * 
   * @param context
   *          the Hadoop context for the configured job
   * @return true if the connector has been configured, false otherwise
   * @since 1.5.0
   * @see #setConnectorInfo(Job, String, AuthenticationToken)
   */
  protected static Boolean isConnectorInfoSet(JobContext context) {
    return OutputConfigurator.isConnectorInfoSet(CLASS, InputFormatBase.getConfiguration(context));
  }

  /**
   * Gets the user name from the configuration.
   * 
   * @param context
   *          the Hadoop context for the configured job
   * @return the user name
   * @since 1.5.0
   * @see #setConnectorInfo(Job, String, AuthenticationToken)
   */
  protected static String getPrincipal(JobContext context) {
    return OutputConfigurator.getPrincipal(CLASS, InputFormatBase.getConfiguration(context));
  }

  /**
   * Gets the serialized token class from either the configuration or the token file.
   * 
   * @since 1.5.0
   * @deprecated since 1.6.0; Use {@link #getAuthenticationToken(JobContext)} instead.
   */
  @Deprecated
  protected static String getTokenClass(JobContext context) {
    return getAuthenticationToken(context).getClass().getName();
  }

  /**
   * Gets the serialized token from either the configuration or the token file.
   * 
   * @since 1.5.0
   * @deprecated since 1.6.0; Use {@link #getAuthenticationToken(JobContext)} instead.
   */
  @Deprecated
  protected static byte[] getToken(JobContext context) {
    return AuthenticationTokenSerializer.serialize(getAuthenticationToken(context));
  }

  /**
   * Gets the authenticated token from either the specified token file or directly from the configuration, whichever was used when the job was configured.
   * 
   * @param context
   *          the Hadoop context for the configured job
   * @return the principal's authentication token
   * @since 1.6.0
   * @see #setConnectorInfo(Job, String, AuthenticationToken)
   * @see #setConnectorInfo(Job, String, String)
   */
  protected static AuthenticationToken getAuthenticationToken(JobContext context) {
    return OutputConfigurator.getAuthenticationToken(CLASS, InputFormatBase.getConfiguration(context));
  }

  /**
   * Configures a {@link ZooKeeperInstance} for this job.
   * 
   * @param job
   *          the Hadoop job instance to be configured
   * @param instanceName
   *          the Accumulo instance name
   * @param zooKeepers
   *          a comma-separated list of zookeeper servers
   * @since 1.5.0
   * @deprecated since 1.6.0; Use {@link #setZooKeeperInstance(Job, ClientConfiguration)} instead.
   */
  @Deprecated
  public static void setZooKeeperInstance(Job job, String instanceName, String zooKeepers) {
    OutputConfigurator.setZooKeeperInstance(CLASS, job.getConfiguration(), instanceName, zooKeepers);
  }

  /**
   * Configures a {@link ZooKeeperInstance} for this job.
   * 
   * @param job
   *          the Hadoop job instance to be configured
   * @param clientConfig
   *          client configuration for specifying connection timeouts, SSL connection options, etc.
   * @since 1.6.0
   */
  public static void setZooKeeperInstance(Job job, ClientConfiguration clientConfig) {
    OutputConfigurator.setZooKeeperInstance(CLASS, job.getConfiguration(), clientConfig);
  }

  /**
   * Configures a {@link MockInstance} for this job.
   * 
   * @param job
   *          the Hadoop job instance to be configured
   * @param instanceName
   *          the Accumulo instance name
   * @since 1.5.0
   */
  public static void setMockInstance(Job job, String instanceName) {
    OutputConfigurator.setMockInstance(CLASS, job.getConfiguration(), instanceName);
  }

  /**
   * Initializes an Accumulo {@link Instance} based on the configuration.
   * 
   * @param context
   *          the Hadoop context for the configured job
   * @return an Accumulo instance
   * @since 1.5.0
   * @see #setZooKeeperInstance(Job, ClientConfiguration)
   * @see #setMockInstance(Job, String)
   */
  protected static Instance getInstance(JobContext context) {
    return OutputConfigurator.getInstance(CLASS, InputFormatBase.getConfiguration(context));
  }

  /**
   * Sets the log level for this job.
   * 
   * @param job
   *          the Hadoop job instance to be configured
   * @param level
   *          the logging level
   * @since 1.5.0
   */
  public static void setLogLevel(Job job, Level level) {
    OutputConfigurator.setLogLevel(CLASS, job.getConfiguration(), level);
  }

  /**
   * Gets the log level from this configuration.
   * 
   * @param context
   *          the Hadoop context for the configured job
   * @return the log level
   * @since 1.5.0
   * @see #setLogLevel(Job, Level)
   */
  protected static Level getLogLevel(JobContext context) {
    return OutputConfigurator.getLogLevel(CLASS, InputFormatBase.getConfiguration(context));
  }

  /**
   * Sets the default table name to use if one emits a null in place of a table name for a given mutation. Table names can only be alpha-numeric and
   * underscores.
   * 
   * @param job
   *          the Hadoop job instance to be configured
   * @param tableName
   *          the table to use when the tablename is null in the write call
   * @since 1.5.0
   */
  public static void setDefaultTableName(Job job, String tableName) {
    OutputConfigurator.setDefaultTableName(CLASS, job.getConfiguration(), tableName);
  }

  /**
   * Gets the default table name from the configuration.
   * 
   * @param context
   *          the Hadoop context for the configured job
   * @return the default table name
   * @since 1.5.0
   * @see #setDefaultTableName(Job, String)
   */
  protected static String getDefaultTableName(JobContext context) {
    return OutputConfigurator.getDefaultTableName(CLASS, InputFormatBase.getConfiguration(context));
  }

  /**
   * Sets the configuration for for the job's {@link BatchWriter} instances. If not set, a new {@link BatchWriterConfig}, with sensible built-in defaults is
   * used. Setting the configuration multiple times overwrites any previous configuration.
   * 
   * @param job
   *          the Hadoop job instance to be configured
   * @param bwConfig
   *          the configuration for the {@link BatchWriter}
   * @since 1.5.0
   */
  public static void setBatchWriterOptions(Job job, BatchWriterConfig bwConfig) {
    OutputConfigurator.setBatchWriterOptions(CLASS, job.getConfiguration(), bwConfig);
  }

  /**
   * Gets the {@link BatchWriterConfig} settings.
   * 
   * @param context
   *          the Hadoop context for the configured job
   * @return the configuration object
   * @since 1.5.0
   * @see #setBatchWriterOptions(Job, BatchWriterConfig)
   */
  protected static BatchWriterConfig getBatchWriterOptions(JobContext context) {
    return OutputConfigurator.getBatchWriterOptions(CLASS, InputFormatBase.getConfiguration(context));
  }

  /**
   * Sets the directive to create new tables, as necessary. Table names can only be alpha-numeric and underscores.
   * 
   * <p>
   * By default, this feature is <b>disabled</b>.
   * 
   * @param job
   *          the Hadoop job instance to be configured
   * @param enableFeature
   *          the feature is enabled if true, disabled otherwise
   * @since 1.5.0
   */
  public static void setCreateTables(Job job, boolean enableFeature) {
    OutputConfigurator.setCreateTables(CLASS, job.getConfiguration(), enableFeature);
  }

  /**
   * Determines whether tables are permitted to be created as needed.
   * 
   * @param context
   *          the Hadoop context for the configured job
   * @return true if the feature is disabled, false otherwise
   * @since 1.5.0
   * @see #setCreateTables(Job, boolean)
   */
  protected static Boolean canCreateTables(JobContext context) {
    return OutputConfigurator.canCreateTables(CLASS, InputFormatBase.getConfiguration(context));
  }

  /**
   * Sets the directive to use simulation mode for this job. In simulation mode, no output is produced. This is useful for testing.
   * 
   * <p>
   * By default, this feature is <b>disabled</b>.
   * 
   * @param job
   *          the Hadoop job instance to be configured
   * @param enableFeature
   *          the feature is enabled if true, disabled otherwise
   * @since 1.5.0
   */
  public static void setSimulationMode(Job job, boolean enableFeature) {
    OutputConfigurator.setSimulationMode(CLASS, job.getConfiguration(), enableFeature);
  }

  /**
   * Determines whether this feature is enabled.
   * 
   * @param context
   *          the Hadoop context for the configured job
   * @return true if the feature is enabled, false otherwise
   * @since 1.5.0
   * @see #setSimulationMode(Job, boolean)
   */
  protected static Boolean getSimulationMode(JobContext context) {
    return OutputConfigurator.getSimulationMode(CLASS, InputFormatBase.getConfiguration(context));
  }

  /**
   * A base class to be used to create {@link RecordWriter} instances that write to Accumulo.
   */
  protected static class AccumuloRecordWriter extends RecordWriter<Text,Mutation> {
    private MultiTableBatchWriter mtbw = null;
    private HashMap<Text,BatchWriter> bws = null;
    private Text defaultTableName = null;

    private boolean simulate = false;
    private boolean createTables = false;

    private long mutCount = 0;
    private long valCount = 0;

    private Connector conn;

    protected AccumuloRecordWriter(TaskAttemptContext context) throws AccumuloException, AccumuloSecurityException, IOException {
      Level l = getLogLevel(context);
      if (l != null)
        log.setLevel(getLogLevel(context));
      this.simulate = getSimulationMode(context);
      this.createTables = canCreateTables(context);

      if (simulate)
        log.info("Simulating output only. No writes to tables will occur");

      this.bws = new HashMap<Text,BatchWriter>();

      String tname = getDefaultTableName(context);
      this.defaultTableName = (tname == null) ? null : new Text(tname);

      if (!simulate) {
        this.conn = getInstance(context).getConnector(getPrincipal(context), getAuthenticationToken(context));
        mtbw = conn.createMultiTableBatchWriter(getBatchWriterOptions(context));
      }
    }

    /**
     * Push a mutation into a table. If table is null, the defaultTable will be used. If canCreateTable is set, the table will be created if it does not exist.
     * The table name must only contain alphanumerics and underscore.
     */
    @Override
    public void write(Text table, Mutation mutation) throws IOException {
      if (table == null || table.toString().isEmpty())
        table = this.defaultTableName;

      if (!simulate && table == null)
        throw new IOException("No table or default table specified. Try simulation mode next time");

      ++mutCount;
      valCount += mutation.size();
      printMutation(table, mutation);

      if (simulate)
        return;

      if (!bws.containsKey(table))
        try {
          addTable(table);
        } catch (Exception e) {
          e.printStackTrace();
          throw new IOException(e);
        }

      try {
        bws.get(table).addMutation(mutation);
      } catch (MutationsRejectedException e) {
        throw new IOException(e);
      }
    }

    public void addTable(Text tableName) throws AccumuloException, AccumuloSecurityException {
      if (simulate) {
        log.info("Simulating adding table: " + tableName);
        return;
      }

      log.debug("Adding table: " + tableName);
      BatchWriter bw = null;
      String table = tableName.toString();

      if (createTables && !conn.tableOperations().exists(table)) {
        try {
          conn.tableOperations().create(table);
        } catch (AccumuloSecurityException e) {
          log.error("Accumulo security violation creating " + table, e);
          throw e;
        } catch (TableExistsException e) {
          // Shouldn't happen
        }
      }

      try {
        bw = mtbw.getBatchWriter(table);
      } catch (TableNotFoundException e) {
        log.error("Accumulo table " + table + " doesn't exist and cannot be created.", e);
        throw new AccumuloException(e);
      } catch (AccumuloException e) {
        throw e;
      } catch (AccumuloSecurityException e) {
        throw e;
      }

      if (bw != null)
        bws.put(tableName, bw);
    }

    private int printMutation(Text table, Mutation m) {
      if (log.isTraceEnabled()) {
        log.trace(String.format("Table %s row key: %s", table, hexDump(m.getRow())));
        for (ColumnUpdate cu : m.getUpdates()) {
          log.trace(String.format("Table %s column: %s:%s", table, hexDump(cu.getColumnFamily()), hexDump(cu.getColumnQualifier())));
          log.trace(String.format("Table %s security: %s", table, new ColumnVisibility(cu.getColumnVisibility()).toString()));
          log.trace(String.format("Table %s value: %s", table, hexDump(cu.getValue())));
        }
      }
      return m.getUpdates().size();
    }

    private String hexDump(byte[] ba) {
      StringBuilder sb = new StringBuilder();
      for (byte b : ba) {
        if ((b > 0x20) && (b < 0x7e))
          sb.append((char) b);
        else
          sb.append(String.format("x%02x", b));
      }
      return sb.toString();
    }

    @Override
    public void close(TaskAttemptContext attempt) throws IOException, InterruptedException {
      log.debug("mutations written: " + mutCount + ", values written: " + valCount);
      if (simulate)
        return;

      try {
        mtbw.close();
      } catch (MutationsRejectedException e) {
        if (e.getAuthorizationFailuresMap().size() >= 0) {
          HashMap<String,Set<SecurityErrorCode>> tables = new HashMap<String,Set<SecurityErrorCode>>();
          for (Entry<KeyExtent,Set<SecurityErrorCode>> ke : e.getAuthorizationFailuresMap().entrySet()) {
            Set<SecurityErrorCode> secCodes = tables.get(ke.getKey().getTableId().toString());
            if (secCodes == null) {
              secCodes = new HashSet<SecurityErrorCode>();
              tables.put(ke.getKey().getTableId().toString(), secCodes);
            }
            secCodes.addAll(ke.getValue());
          }

          log.error("Not authorized to write to tables : " + tables);
        }

        if (e.getConstraintViolationSummaries().size() > 0) {
          log.error("Constraint violations : " + e.getConstraintViolationSummaries().size());
        }
      }
    }
  }

  @Override
  public void checkOutputSpecs(JobContext job) throws IOException {
    if (!isConnectorInfoSet(job))
      throw new IOException("Connector info has not been set.");
    try {
      // if the instance isn't configured, it will complain here
      String principal = getPrincipal(job);
      AuthenticationToken token = getAuthenticationToken(job);
      Connector c = getInstance(job).getConnector(principal, token);
      if (!c.securityOperations().authenticateUser(principal, token))
        throw new IOException("Unable to authenticate user");
    } catch (AccumuloException e) {
      throw new IOException(e);
    } catch (AccumuloSecurityException e) {
      throw new IOException(e);
    }
  }

  @Override
  public OutputCommitter getOutputCommitter(TaskAttemptContext context) {
    return new NullOutputFormat<Text,Mutation>().getOutputCommitter(context);
  }

  @Override
  public RecordWriter<Text,Mutation> getRecordWriter(TaskAttemptContext attempt) throws IOException {
    try {
      return new AccumuloRecordWriter(attempt);
    } catch (Exception e) {
      throw new IOException(e);
    }
  }

}
