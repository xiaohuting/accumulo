/**
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
package org.apache.accumulo.server.security;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Set;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.client.admin.SecurityOperationsImpl;
import org.apache.accumulo.core.client.impl.thrift.ThriftTableOperationException;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.SystemPermission;
import org.apache.accumulo.core.security.TablePermission;
import org.apache.accumulo.core.security.thrift.AuthInfo;
import org.apache.accumulo.core.security.thrift.SecurityErrorCode;
import org.apache.accumulo.core.security.thrift.ThriftSecurityException;
import org.apache.accumulo.server.client.HdfsZooInstance;
import org.apache.accumulo.server.master.Master;
import org.apache.accumulo.server.zookeeper.ZooCache;
import org.apache.log4j.Logger;

/**
 * Utility class for performing various security operations with the appropriate checks
 */
public class SecurityOperationImpl implements SecurityOperation {
  private static final Logger log = Logger.getLogger(SecurityOperationsImpl.class);

  private static Authorizor authorizor;
  private static Authenticator authenticator;
  private static String rootUserName = null;
  private final ZooCache zooCache;
  private final String ZKUserPath;
  
  private static SecurityOperation instance;
  
  public static synchronized SecurityOperation getInstance() {
    String instanceId = HdfsZooInstance.getInstance().getInstanceID();
    return getInstance(instanceId);
  }
  
  public static synchronized SecurityOperation getInstance(String instanceId) {
    if (instance == null) {
      instance = new AuditedSecurityOperation(new SecurityOperationImpl(getAuthorizor(instanceId), getAuthenticator(instanceId), instanceId));
    }
    return instance;
  }
  
  @SuppressWarnings("deprecation")
  private static Authorizor getAuthorizor(String instanceId) {
    Authorizor toRet = Master.createInstanceFromPropertyName(AccumuloConfiguration.getSiteConfiguration(), Property.INSTANCE_SECURITY_AUTHORIZOR,
        Authorizor.class, ZKAuthorizor.getInstance());
    toRet.initialize(instanceId);
    return toRet;
  }

  @SuppressWarnings("deprecation")
  private static Authenticator getAuthenticator(String instanceId) {
    Authenticator toRet = Master.createInstanceFromPropertyName(AccumuloConfiguration.getSiteConfiguration(), Property.INSTANCE_SECURITY_AUTHENTICATOR,
        Authenticator.class, ZKAuthenticator.getInstance());
    toRet.initialize(instanceId);
    return toRet;
  }

  public SecurityOperationImpl(Authorizor author, Authenticator authent, String instanceId) {
    authorizor = author;
    authenticator = authent;
    
    if (!authorizor.validAuthenticator(authenticator) || !authenticator.validAuthorizor(authorizor))
      throw new RuntimeException(authorizor + " and " + authenticator
          + " do not play nice with eachother. Please choose authentication and authorization mechanisms that are compatible with one another.");
    
    ZKUserPath = Constants.ZROOT + "/" + instanceId + "/users";
    zooCache = new ZooCache();
  }
  
  public void initializeSecurity(AuthInfo credentials, String rootuser, byte[] rootpass) throws AccumuloSecurityException, ThriftSecurityException {
    authenticate(credentials);

    if (!credentials.user.equals(SecurityConstants.SYSTEM_USERNAME))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);

    authenticator.initializeSecurity(credentials, rootuser, rootpass);
    authorizor.initializeSecurity(rootuser);
    try {
      authorizor.grantTablePermission(rootuser, Constants.METADATA_TABLE_ID, TablePermission.ALTER_TABLE);
    } catch (TableNotFoundException e) {
      // Shouldn't happen
      throw new RuntimeException(e);
    }
  }

  public synchronized String getRootUsername() {
    if (rootUserName == null)
      rootUserName = new String(zooCache.get(ZKUserPath));
    return rootUserName;
  }
  
  private void authenticate(String user, ByteBuffer password, String instance) throws ThriftSecurityException {
    if (!instance.equals(HdfsZooInstance.getInstance().getInstanceID()))
      throw new ThriftSecurityException(user, SecurityErrorCode.INVALID_INSTANCEID);
    
    if (user.equals(SecurityConstants.SYSTEM_USERNAME)) {
      if (Arrays.equals(SecurityConstants.getSystemCredentials().password.array(), password.array())
          && instance.equals(SecurityConstants.getSystemCredentials().instanceId))
        return;
      else
        throw new ThriftSecurityException(user, SecurityErrorCode.BAD_CREDENTIALS);
    }
    
    if (!authenticator.authenticateUser(user, password, instance))
      throw new ThriftSecurityException(user, SecurityErrorCode.BAD_CREDENTIALS);
  }
  
  private void authenticate(AuthInfo credentials) throws ThriftSecurityException {
    authenticate(credentials.user, credentials.password, credentials.instanceId);
  }

  /**
   * @param credentials
   * @param user
   * @param password
   * @return
   * @throws ThriftSecurityException
   */
  public boolean authenticateUser(AuthInfo credentials, String user, ByteBuffer password) throws ThriftSecurityException {
    authenticate(credentials);
    
    if (credentials.user.equals(user))
      return true;
    
    if (!canPerformSystemActions(credentials))
      throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    return authenticator.authenticateUser(user, password, credentials.instanceId);
    
  }
  
  /**
   * @param credentials
   * @param user
   * @return The given user's authorizations
   * @throws ThriftSecurityException
   */
  public Authorizations getUserAuthorizations(AuthInfo credentials, String user) throws ThriftSecurityException {
    authenticate(credentials);
    
    targetUserExists(user);

    if (!credentials.user.equals(user) && !hasSystemPermission(credentials.user, SystemPermission.SYSTEM))
      throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    // system user doesn't need record-level authorizations for the tables it reads (for now)
    if (user.equals(SecurityConstants.SYSTEM_USERNAME))
      return Constants.NO_AUTHS;
    
    try {
      return authorizor.getUserAuthorizations(user);
    } catch (AccumuloSecurityException e) {
      throw e.asThriftException();
    }
  }

  /**
   * @param credentials
   * @return
   * @throws ThriftSecurityException
   */
  public Authorizations getUserAuthorizations(AuthInfo credentials) throws ThriftSecurityException {
    return getUserAuthorizations(credentials, credentials.user);
  }
  
  /**
   * Checks if a user has a system permission
   * 
   * @return true if a user exists and has permission; false otherwise
   */
  private boolean hasSystemPermission(String user, SystemPermission permission) throws ThriftSecurityException {
    if (user.equals(getRootUsername()) || user.equals(SecurityConstants.SYSTEM_USERNAME))
      return true;
    
    targetUserExists(user);

    try {
      return authorizor.hasSystemPermission(user, permission);
    } catch (AccumuloSecurityException e) {
      throw e.asThriftException();
    }
  }

  /**
   * Checks if a user has a table permission
   * 
   * @return true if a user exists and has permission; false otherwise
   * @throws ThriftTableOperationException
   */
  private boolean hasTablePermission(String user, String table, TablePermission permission) throws ThriftSecurityException {
    if (user.equals(SecurityConstants.SYSTEM_USERNAME))
      return true;
    
    targetUserExists(user);

    if (table.equals(Constants.METADATA_TABLE_ID) && permission.equals(TablePermission.READ))
      return true;

    try {
      return authorizor.hasTablePermission(user, table, permission);
    } catch (AccumuloSecurityException e) {
      throw e.asThriftException();
    } catch (TableNotFoundException e) {
      throw new ThriftSecurityException(user, SecurityErrorCode.TABLE_DOESNT_EXIST);
    }
  }
  
  // some people just aren't allowed to ask about other users; here are those who can ask
  private boolean canAskAboutOtherUsers(AuthInfo credentials, String user) throws ThriftSecurityException {
    authenticate(credentials);
    return credentials.user.equals(user) || hasSystemPermission(credentials.user, SystemPermission.SYSTEM)
        || hasSystemPermission(credentials, credentials.user, SystemPermission.CREATE_USER)
        || hasSystemPermission(credentials, credentials.user, SystemPermission.ALTER_USER)
        || hasSystemPermission(credentials, credentials.user, SystemPermission.DROP_USER);
  }
  
  /**
   * @param user
   * @throws ThriftSecurityException
   */
  private void targetUserExists(String user) throws ThriftSecurityException {
    if (user.equals(SecurityConstants.SYSTEM_USERNAME) || user.equals(getRootUsername()))
      return;

    if (!authenticator.userExists(user))
      throw new ThriftSecurityException(user, SecurityErrorCode.USER_DOESNT_EXIST);
  }
  
  /**
   * @param credentials
   * @param string
   * @return
   * @throws ThriftSecurityException
   * @throws TableNotFoundException
   */
  public boolean canScan(AuthInfo credentials, String table) throws ThriftSecurityException {
    authenticate(credentials);
    return hasTablePermission(credentials.user, table, TablePermission.READ);
  }

  /**
   * @param credentials
   * @param string
   * @return
   * @throws ThriftSecurityException
   * @throws TableNotFoundException
   */
  public boolean canWrite(AuthInfo credentials, String table) throws ThriftSecurityException {
    authenticate(credentials);
    return hasTablePermission(credentials.user, table, TablePermission.WRITE);
  }

  /**
   * @param credentials
   * @param string
   * @return
   * @throws ThriftSecurityException
   * @throws TableNotFoundException
   */
  public boolean canSplitTablet(AuthInfo credentials, String table) throws ThriftSecurityException {
    authenticate(credentials);
    return hasSystemPermission(credentials.user, SystemPermission.ALTER_TABLE) || hasSystemPermission(credentials.user, SystemPermission.SYSTEM)
        || hasTablePermission(credentials.user, table, TablePermission.ALTER_TABLE);
  }
  
  /**
   * @param credentials
   * @return
   * @throws ThriftSecurityException
   * 
   *           This is the check to perform any system action. This includes tserver's loading of a tablet, shutting the system down, or altering system
   *           properties.
   */
  public boolean canPerformSystemActions(AuthInfo credentials) throws ThriftSecurityException {
    authenticate(credentials);
    return hasSystemPermission(credentials.user, SystemPermission.SYSTEM);
  }

  /**
   * @param c
   * @param tableId
   * @throws ThriftSecurityException
   * @throws ThriftTableOperationException
   */
  public boolean canFlush(AuthInfo c, String tableId) throws ThriftSecurityException {
    authenticate(c);
    return hasTablePermission(c.user, tableId, TablePermission.WRITE) || hasTablePermission(c.user, tableId, TablePermission.ALTER_TABLE);
  }
  
  /**
   * @param c
   * @param tableId
   * @throws ThriftSecurityException
   * @throws ThriftTableOperationException
   */
  public boolean canAlterTable(AuthInfo c, String tableId) throws ThriftSecurityException {
    authenticate(c);
    return hasTablePermission(c.user, tableId, TablePermission.ALTER_TABLE) || hasSystemPermission(c.user, SystemPermission.ALTER_TABLE);
  }
  
  /**
   * @param c
   * @throws ThriftSecurityException
   */
  public boolean canCreateTable(AuthInfo c) throws ThriftSecurityException {
    authenticate(c);
    return hasSystemPermission(c.user, SystemPermission.CREATE_TABLE);
  }
  
  /**
   * @param c
   * @param tableId
   * @return
   * @throws TableNotFoundException
   * @throws ThriftSecurityException
   */
  public boolean canRenameTable(AuthInfo c, String tableId) throws ThriftSecurityException {
    authenticate(c);
    return hasSystemPermission(c.user, SystemPermission.ALTER_TABLE) || hasTablePermission(c.user, tableId, TablePermission.ALTER_TABLE);
  }
  
  /**
   * @param c
   * @return
   * @throws TableNotFoundException
   * @throws ThriftSecurityException
   */
  public boolean canCloneTable(AuthInfo c, String tableId) throws ThriftSecurityException {
    authenticate(c);
    return hasSystemPermission(c.user, SystemPermission.CREATE_TABLE) && hasTablePermission(c.user, tableId, TablePermission.READ);
  }
  
  /**
   * @param c
   * @param tableId
   * @return
   * @throws TableNotFoundException
   * @throws ThriftSecurityException
   */
  public boolean canDeleteTable(AuthInfo c, String tableId) throws ThriftSecurityException {
    authenticate(c);
    return hasSystemPermission(c.user, SystemPermission.DROP_TABLE) || hasTablePermission(c.user, tableId, TablePermission.DROP_TABLE);
  }
  
  /**
   * @param c
   * @param tableId
   * @return
   * @throws TableNotFoundException
   * @throws ThriftSecurityException
   */
  public boolean canOnlineOfflineTable(AuthInfo c, String tableId) throws ThriftSecurityException {
    authenticate(c);
    return hasSystemPermission(c.user, SystemPermission.SYSTEM) || hasSystemPermission(c.user, SystemPermission.ALTER_TABLE)
        || hasTablePermission(c.user, tableId, TablePermission.ALTER_TABLE);
  }
  
  /**
   * @param c
   * @param tableId
   * @return
   * @throws TableNotFoundException
   * @throws ThriftSecurityException
   */
  public boolean canMerge(AuthInfo c, String tableId) throws ThriftSecurityException {
    authenticate(c);
    return hasSystemPermission(c.user, SystemPermission.SYSTEM) || hasSystemPermission(c.user, SystemPermission.ALTER_TABLE)
        || hasTablePermission(c.user, tableId, TablePermission.ALTER_TABLE);
  }
  
  /**
   * @param c
   * @param tableId
   * @return
   * @throws TableNotFoundException
   * @throws ThriftSecurityException
   */
  public boolean canDeleteRange(AuthInfo c, String tableId) throws ThriftSecurityException {
    authenticate(c);
    return hasSystemPermission(c.user, SystemPermission.SYSTEM) || hasTablePermission(c.user, tableId, TablePermission.WRITE);
  }
  
  /**
   * @param c
   * @param tableId
   * @return
   * @throws TableNotFoundException
   * @throws ThriftSecurityException
   */
  public boolean canBulkImport(AuthInfo c, String tableId) throws ThriftSecurityException {
    authenticate(c);
    return hasTablePermission(c.user, tableId, TablePermission.BULK_IMPORT);
  }
  
  /**
   * @param c
   * @param tableId
   * @return
   * @throws TableNotFoundException
   * @throws ThriftSecurityException
   */
  public boolean canCompact(AuthInfo c, String tableId) throws ThriftSecurityException {
    authenticate(c);
    return hasSystemPermission(c.user, SystemPermission.ALTER_TABLE) || hasTablePermission(c.user, tableId, TablePermission.ALTER_TABLE)
        || hasTablePermission(c.user, tableId, TablePermission.WRITE);
  }
  
  /**
   * @param credentials
   * @return
   * @throws ThriftSecurityException
   */
  public boolean canChangeAuthorizations(AuthInfo c, String user) throws ThriftSecurityException {
    authenticate(c);
    if (user.equals(SecurityConstants.SYSTEM_USERNAME))
      throw new ThriftSecurityException(c.user, SecurityErrorCode.PERMISSION_DENIED);
    return hasSystemPermission(c.user, SystemPermission.ALTER_USER);
  }

  /**
   * @param credentials
   * @param user
   * @return
   * @throws ThriftSecurityException
   */
  public boolean canChangePassword(AuthInfo c, String user) throws ThriftSecurityException {
    authenticate(c);
    if (user.equals(SecurityConstants.SYSTEM_USERNAME))
      throw new ThriftSecurityException(c.user, SecurityErrorCode.PERMISSION_DENIED);
    return c.user.equals(user) || hasSystemPermission(c.user, SystemPermission.ALTER_TABLE);
  }
  
  /**
   * @param credentials
   * @param user
   * @return
   * @throws ThriftSecurityException
   */
  public boolean canCreateUser(AuthInfo c, String user) throws ThriftSecurityException {
    authenticate(c);
    
    // don't allow creating a user with the same name as system user
    if (user.equals(SecurityConstants.SYSTEM_USERNAME))
      throw new ThriftSecurityException(user, SecurityErrorCode.PERMISSION_DENIED);

    return hasSystemPermission(c.user, SystemPermission.CREATE_USER);
  }

  /**
   * @param credentials
   * @param user
   * @return
   * @throws ThriftSecurityException
   */
  public boolean canDropUser(AuthInfo c, String user) throws ThriftSecurityException {
    authenticate(c);
    
    // can't delete root or system users
    if (user.equals(getRootUsername()) || user.equals(SecurityConstants.SYSTEM_USERNAME))
      throw new ThriftSecurityException(user, SecurityErrorCode.PERMISSION_DENIED);

    return hasSystemPermission(c.user, SystemPermission.DROP_USER);
  }
  
  /**
   * @param credentials
   * @param user
   * @param sysPerm
   * @return
   * @throws ThriftSecurityException
   */
  public boolean canGrantSystem(AuthInfo c, String user, SystemPermission sysPerm) throws ThriftSecurityException {
    authenticate(c);
    
    // can't modify system user
    if (user.equals(SecurityConstants.SYSTEM_USERNAME))
      throw new ThriftSecurityException(c.user, SecurityErrorCode.PERMISSION_DENIED);
    
    // can't grant GRANT
    if (sysPerm.equals(SystemPermission.GRANT))
      throw new ThriftSecurityException(c.user, SecurityErrorCode.GRANT_INVALID);

    return hasSystemPermission(c.user, SystemPermission.GRANT);
  }
  
  /**
   * @param credentials
   * @param user
   * @param table
   * @return
   * @throws ThriftSecurityException
   */
  public boolean canGrantTable(AuthInfo c, String user, String table) throws ThriftSecurityException {
    authenticate(c);
    
    // can't modify system user
    if (user.equals(SecurityConstants.SYSTEM_USERNAME))
      throw new ThriftSecurityException(c.user, SecurityErrorCode.PERMISSION_DENIED);
    
    return hasSystemPermission(c.user, SystemPermission.ALTER_TABLE) || hasTablePermission(c.user, table, TablePermission.GRANT);
  }
  
  /**
   * @param credentials
   * @param user
   * @param sysPerm
   * @return
   * @throws ThriftSecurityException
   */
  public boolean canRevokeSystem(AuthInfo c, String user, SystemPermission sysPerm) throws ThriftSecurityException {
    authenticate(c);
    
    // can't modify system or root user
    if (user.equals(getRootUsername()) || user.equals(SecurityConstants.SYSTEM_USERNAME))
      throw new ThriftSecurityException(c.user, SecurityErrorCode.PERMISSION_DENIED);
    
    // can't revoke GRANT
    if (sysPerm.equals(SystemPermission.GRANT))
      throw new ThriftSecurityException(c.user, SecurityErrorCode.GRANT_INVALID);
    
    return hasSystemPermission(c.user, SystemPermission.GRANT);
  }
  
  /**
   * @param credentials
   * @param user
   * @param table
   * @return
   * @throws ThriftSecurityException
   */
  public boolean canRevokeTable(AuthInfo c, String user, String table) throws ThriftSecurityException {
    authenticate(c);
    
    // can't modify system user
    if (user.equals(SecurityConstants.SYSTEM_USERNAME))
      throw new ThriftSecurityException(c.user, SecurityErrorCode.PERMISSION_DENIED);
    
    return hasSystemPermission(c.user, SystemPermission.ALTER_TABLE) || hasTablePermission(c.user, table, TablePermission.GRANT);
  }
  
  /**
   * @param credentials
   * @param user
   * @param authorizations
   * @throws ThriftSecurityException
   */
  public void changeAuthorizations(AuthInfo credentials, String user, Authorizations authorizations) throws ThriftSecurityException {
    if (!canChangeAuthorizations(credentials, user))
      throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    targetUserExists(user);

    try {
      authorizor.changeAuthorizations(user, authorizations);
      log.info("Changed authorizations for user " + user + " at the request of user " + credentials.user);
    } catch (AccumuloSecurityException ase) {
      throw ase.asThriftException();
    }
  }
  
  /**
   * @param credentials
   * @param user
   * @param bytes
   * @throws ThriftSecurityException
   */
  public void changePassword(AuthInfo credentials, String user, byte[] pass) throws ThriftSecurityException {
    if (!canChangePassword(credentials, user))
      throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    try {
      authenticator.changePassword(user, pass);
      log.info("Changed password for user " + user + " at the request of user " + credentials.user);
    } catch (AccumuloSecurityException e) {
      throw e.asThriftException();
    }
  }
  
  /**
   * @param credentials
   * @param user
   * @param bytes
   * @param authorizations
   * @throws ThriftSecurityException
   */
  public void createUser(AuthInfo credentials, String user, byte[] pass, Authorizations authorizations) throws ThriftSecurityException {
    if (!canCreateUser(credentials, user))
      throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    try {
      authenticator.createUser(user, pass);
      authorizor.initUser(user);
      log.info("Created user " + user + " at the request of user " + credentials.user);
      if (canChangeAuthorizations(credentials, user))
        authorizor.changeAuthorizations(user, authorizations);
    } catch (AccumuloSecurityException ase) {
      throw ase.asThriftException();
    }
  }
  
  /**
   * @param credentials
   * @param user
   * @throws ThriftSecurityException
   */
  public void dropUser(AuthInfo credentials, String user) throws ThriftSecurityException {
    if (!canDropUser(credentials, user))
      throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    try {
      authorizor.dropUser(user);
      authenticator.dropUser(user);
      log.info("Deleted user " + user + " at the request of user " + credentials.user);
    } catch (AccumuloSecurityException e) {
      throw e.asThriftException();
    }
  }
  
  /**
   * @param credentials
   * @param user
   * @param permissionById
   * @throws ThriftSecurityException
   */
  public void grantSystemPermission(AuthInfo credentials, String user, SystemPermission permissionById) throws ThriftSecurityException {
    if (!canGrantSystem(credentials, user, permissionById))
      throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    targetUserExists(user);

    try {
      authorizor.grantSystemPermission(user, permissionById);
      log.info("Granted system permission " + permissionById + " for user " + user + " at the request of user " + credentials.user);
    } catch (AccumuloSecurityException e) {
      throw e.asThriftException();
    }
  }
  
  /**
   * @param credentials
   * @param user
   * @param tableId
   * @param permission
   * @throws ThriftSecurityException
   */
  public void grantTablePermission(AuthInfo c, String user, String tableId, TablePermission permission) throws ThriftSecurityException {
    if (!canGrantTable(c, user, tableId))
      throw new ThriftSecurityException(c.user, SecurityErrorCode.PERMISSION_DENIED);
    
    targetUserExists(user);

    try {
      authorizor.grantTablePermission(user, tableId, permission);
      log.info("Granted table permission " + permission + " for user " + user + " on the table " + tableId + " at the request of user " + c.user);
    } catch (AccumuloSecurityException e) {
      throw e.asThriftException();
    } catch (TableNotFoundException e) {
      throw new ThriftSecurityException(c.user, SecurityErrorCode.TABLE_DOESNT_EXIST);
    }
  }
  
  /**
   * @param credentials
   * @param user
   * @param permission
   * @throws ThriftSecurityException
   */
  public void revokeSystemPermission(AuthInfo credentials, String user, SystemPermission permission) throws ThriftSecurityException {
    if (!canRevokeSystem(credentials, user, permission))
      throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    targetUserExists(user);

    try {
      authorizor.revokeSystemPermission(user, permission);
      log.info("Revoked system permission " + permission + " for user " + user + " at the request of user " + credentials.user);

    } catch (AccumuloSecurityException e) {
      throw e.asThriftException();
    }
  }
  
  /**
   * @param credentials
   * @param user
   * @param tableId
   * @param permission
   * @throws ThriftSecurityException
   */
  public void revokeTablePermission(AuthInfo c, String user, String tableId, TablePermission permission) throws ThriftSecurityException {
    if (!canRevokeTable(c, user, tableId))
      throw new ThriftSecurityException(c.user, SecurityErrorCode.PERMISSION_DENIED);
    
    targetUserExists(user);

    try {
      authorizor.revokeTablePermission(user, tableId, permission);
      log.info("Revoked table permission " + permission + " for user " + user + " on the table " + tableId + " at the request of user " + c.user);

    } catch (AccumuloSecurityException e) {
      throw e.asThriftException();
    } catch (TableNotFoundException e) {
      throw new ThriftSecurityException(c.user, SecurityErrorCode.TABLE_DOESNT_EXIST);
    }
  }

  /**
   * @param credentials
   * @param user
   * @param permissionById
   * @return
   * @throws ThriftSecurityException
   */
  public boolean hasSystemPermission(AuthInfo credentials, String user, SystemPermission permissionById) throws ThriftSecurityException {
    if (!canAskAboutOtherUsers(credentials, user))
      throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    return hasSystemPermission(user, permissionById);
  }
  
  /**
   * @param credentials
   * @param user
   * @param tableId
   * @param permissionById
   * @return
   * @throws ThriftSecurityException
   */
  public boolean hasTablePermission(AuthInfo credentials, String user, String tableId, TablePermission permissionById) throws ThriftSecurityException {
    if (!canAskAboutOtherUsers(credentials, user))
      throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    return hasTablePermission(user, tableId, permissionById);
  }
  
  /**
   * @param credentials
   * @return
   * @throws ThriftSecurityException
   */
  public Set<String> listUsers(AuthInfo credentials) throws ThriftSecurityException {
    authenticate(credentials);
    try {
      return authenticator.listUsers();
    } catch (AccumuloSecurityException e) {
      throw e.asThriftException();
    }
  }
  
  /**
   * @param systemCredentials
   * @param tableId
   * @throws ThriftSecurityException
   */
  public void deleteTable(AuthInfo credentials, String tableId) throws ThriftSecurityException {
    if (!canDeleteTable(credentials, tableId))
      throw new ThriftSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    try {
      authorizor.cleanTablePermissions(tableId);
    } catch (AccumuloSecurityException e) {
      e.setUser(credentials.user);
      throw e.asThriftException();
    } catch (TableNotFoundException e) {
      throw new ThriftSecurityException(credentials.user, SecurityErrorCode.TABLE_DOESNT_EXIST);
    }
  }
  
  @Override
  public void clearCache(String user, boolean password, boolean auths, boolean system, Set<String> tables) throws ThriftSecurityException {
    if (password)
      authenticator.clearCache(user);

    if (auths || system || tables.size() > 0)
      try {
        authorizor.clearCache(user, auths, system, tables);
      } catch (TableNotFoundException e) {
        throw new ThriftSecurityException(user, SecurityErrorCode.TABLE_DOESNT_EXIST);
      } catch (AccumuloSecurityException e) {
        throw e.asThriftException();
      }
    
  }
  
  @Override
  public void clearCache(String table) throws ThriftSecurityException {
    try {
      authorizor.clearTableCache(table);
    } catch (AccumuloSecurityException e) {
      throw e.asThriftException();
    } catch (TableNotFoundException e) {
      throw new ThriftSecurityException(table, SecurityErrorCode.TABLE_DOESNT_EXIST);
    }
  }
  
  @Override
  public boolean cachesToClear() throws ThriftSecurityException {
    try {
      return authenticator.cachesToClear() || authorizor.cachesToClear();
    } catch (AccumuloSecurityException e) {
      throw e.asThriftException();
    }
  }
}
