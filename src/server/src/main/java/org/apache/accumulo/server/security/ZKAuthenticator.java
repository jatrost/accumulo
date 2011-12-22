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
package org.apache.accumulo.server.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map.Entry;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.AccumuloException;
import org.apache.accumulo.core.client.AccumuloSecurityException;
import org.apache.accumulo.core.client.impl.HdfsZooInstance;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.core.security.SystemPermission;
import org.apache.accumulo.core.security.TablePermission;
import org.apache.accumulo.core.security.thrift.AuthInfo;
import org.apache.accumulo.core.security.thrift.SecurityErrorCode;
import org.apache.accumulo.core.zookeeper.ZooCache;
import org.apache.accumulo.core.zookeeper.ZooUtil;
import org.apache.accumulo.core.zookeeper.ZooUtil.NodeExistsPolicy;
import org.apache.accumulo.core.zookeeper.ZooUtil.NodeMissingPolicy;
import org.apache.log4j.Logger;
import org.apache.zookeeper.KeeperException;

// Utility class for adding all security info into ZK
public final class ZKAuthenticator implements Authenticator {
  private static final Logger log = Logger.getLogger(ZKAuthenticator.class);
  private static ZKAuthenticator zkAuthenticatorInstance = null;
  private static String rootUserName = null;
  
  private final String ZKUserAuths = "/Authorizations";
  private final String ZKUserSysPerms = "/System";
  private final String ZKUserTablePerms = "/Tables";
  
  private final String ZKUserPath;
  private final ZooCache zooCache;
  
  public static synchronized ZKAuthenticator getInstance() {
    if (zkAuthenticatorInstance == null)
      zkAuthenticatorInstance = new ZKAuthenticator();
    return zkAuthenticatorInstance;
  }
  
  private ZKAuthenticator() {
    this(HdfsZooInstance.getInstance().getInstanceID());
  }
  
  public ZKAuthenticator(String instanceId) {
    ZKUserPath = Constants.ZROOT + "/" + instanceId + "/users";
    zooCache = new ZooCache();
  }
  
  /**
   * Authenticate a user's credentials
   * 
   * @return true if username/password combination match existing user; false otherwise
   * @throws AccumuloSecurityException
   */
  private boolean authenticate(AuthInfo credentials) throws AccumuloSecurityException {
    if (!credentials.instanceId.equals(HdfsZooInstance.getInstance().getInstanceID()))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.INVALID_INSTANCEID);
    
    if (credentials.user.equals(SecurityConstants.SYSTEM_USERNAME))
      return credentials.equals(SecurityConstants.systemCredentials);
    
    byte[] pass;
    String zpath = ZKUserPath + "/" + credentials.user;
    pass = zooCache.get(zpath);
    if (pass == null)
      return false;
    return Tool.checkPass(credentials.password, pass);
  }
  
  /**
   * Only SYSTEM user can call this method
   */
  public void initializeSecurity(AuthInfo credentials, String rootuser, byte[] rootpass) throws AccumuloSecurityException {
    if (!credentials.user.equals(SecurityConstants.SYSTEM_USERNAME) || !authenticate(credentials))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    try {
      // remove old settings from zookeeper first, if any
      synchronized (zooCache) {
        zooCache.clear();
        if (ZooUtil.exists(ZKUserPath)) {
          ZooUtil.recursiveDelete(ZKUserPath, NodeMissingPolicy.SKIP);
          log.info("Removed " + ZKUserPath + "/" + " from zookeeper");
        }
        
        // prep parent node of users with root username
        ZooUtil.putPersistentData(ZKUserPath, rootuser.getBytes(), NodeExistsPolicy.FAIL);
        
        // create the root user with all system privileges, no table privileges, and no record-level authorizations
        Set<SystemPermission> rootPerms = new TreeSet<SystemPermission>();
        for (SystemPermission p : SystemPermission.values())
          rootPerms.add(p);
        Map<String,Set<TablePermission>> tablePerms = new HashMap<String,Set<TablePermission>>();
        // Allow the root user to flush the !METADATA table
        tablePerms.put(Constants.METADATA_TABLE_ID, Collections.singleton(TablePermission.ALTER_TABLE));
        constructUser(rootuser, Tool.createPass(rootpass), rootPerms, tablePerms, Constants.NO_AUTHS);
      }
      log.info("Initialized root user with username: " + rootuser + " at the request of user " + credentials.user);
    } catch (KeeperException e) {
      log.error(e, e);
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      log.error(e, e);
      throw new RuntimeException(e);
    } catch (AccumuloException e) {
      log.error(e, e);
      throw new RuntimeException(e);
    }
  }
  
  /**
   * Sets up the user in ZK for the provided user. No checking for existence is done here, it should be done before calling.
   */
  private void constructUser(String user, byte[] pass, Set<SystemPermission> sysPerms, Map<String,Set<TablePermission>> tablePerms, Authorizations auths)
      throws KeeperException, InterruptedException {
    synchronized (zooCache) {
      zooCache.clear();
      ZooUtil.putPersistentData(ZKUserPath + "/" + user, pass, NodeExistsPolicy.FAIL);
      ZooUtil.putPersistentData(ZKUserPath + "/" + user + ZKUserAuths, Tool.convertAuthorizations(auths), NodeExistsPolicy.FAIL);
      ZooUtil.putPersistentData(ZKUserPath + "/" + user + ZKUserSysPerms, Tool.convertSystemPermissions(sysPerms), NodeExistsPolicy.FAIL);
      ZooUtil.putPersistentData(ZKUserPath + "/" + user + ZKUserTablePerms, new byte[0], NodeExistsPolicy.FAIL);
      for (Entry<String,Set<TablePermission>> entry : tablePerms.entrySet())
        createTablePerm(user, entry.getKey(), entry.getValue());
    }
  }
  
  /**
   * Sets up a new table configuration for the provided user/table. No checking for existance is done here, it should be done before calling.
   */
  private void createTablePerm(String user, String table, Set<TablePermission> perms) throws KeeperException, InterruptedException {
    synchronized (zooCache) {
      zooCache.clear();
      ZooUtil.putPersistentData(ZKUserPath + "/" + user + ZKUserTablePerms + "/" + table, Tool.convertTablePermissions(perms), NodeExistsPolicy.FAIL);
    }
  }
  
  public synchronized String getRootUsername() {
    if (rootUserName == null)
      rootUserName = new String(zooCache.get(ZKUserPath));
    return rootUserName;
  }
  
  public boolean authenticateUser(AuthInfo credentials, String user, byte[] pass) throws AccumuloSecurityException {
    if (!authenticate(credentials))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.BAD_CREDENTIALS);
    if (!credentials.user.equals(user) && !hasSystemPermission(credentials, credentials.user, SystemPermission.SYSTEM))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    return authenticate(new AuthInfo(user, pass, credentials.instanceId));
  }
  
  @Override
  public Set<String> listUsers(AuthInfo credentials) throws AccumuloSecurityException {
    if (!authenticate(credentials))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.BAD_CREDENTIALS);
    
    return new TreeSet<String>(zooCache.getChildren(ZKUserPath));
  }
  
  /**
   * Creates a user with no permissions whatsoever
   */
  public void createUser(AuthInfo credentials, String user, byte[] pass, Authorizations authorizations) throws AccumuloSecurityException {
    if (!hasSystemPermission(credentials, credentials.user, SystemPermission.CREATE_USER))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    // don't allow creating a user with the same name as system user
    if (user.equals(SecurityConstants.SYSTEM_USERNAME))
      throw new AccumuloSecurityException(user, SecurityErrorCode.PERMISSION_DENIED);
    
    try {
      constructUser(user, Tool.createPass(pass), new TreeSet<SystemPermission>(), new HashMap<String,Set<TablePermission>>(), authorizations);
      log.info("Created user " + user + " at the request of user " + credentials.user);
    } catch (KeeperException e) {
      log.error(e, e);
      if (e.code().equals(KeeperException.Code.NODEEXISTS))
        throw new AccumuloSecurityException(user, SecurityErrorCode.USER_EXISTS, e);
      throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);
    } catch (InterruptedException e) {
      log.error(e, e);
      throw new RuntimeException(e);
    } catch (AccumuloException e) {
      log.error(e, e);
      throw new AccumuloSecurityException(user, SecurityErrorCode.DEFAULT_SECURITY_ERROR, e);
    }
  }
  
  public void dropUser(AuthInfo credentials, String user) throws AccumuloSecurityException {
    if (!hasSystemPermission(credentials, credentials.user, SystemPermission.DROP_USER))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    // can't delete root or system users
    if (user.equals(getRootUsername()) || user.equals(SecurityConstants.SYSTEM_USERNAME))
      throw new AccumuloSecurityException(user, SecurityErrorCode.PERMISSION_DENIED);
    
    try {
      synchronized (zooCache) {
        zooCache.clear();
        ZooUtil.recursiveDelete(ZKUserPath + "/" + user, NodeMissingPolicy.FAIL);
      }
      log.info("Deleted user " + user + " at the request of user " + credentials.user);
    } catch (InterruptedException e) {
      log.error(e, e);
      throw new RuntimeException(e);
    } catch (KeeperException e) {
      log.error(e, e);
      if (e.code().equals(KeeperException.Code.NONODE))
        throw new AccumuloSecurityException(user, SecurityErrorCode.USER_DOESNT_EXIST, e);
      throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);
    }
  }
  
  public void changePassword(AuthInfo credentials, String user, byte[] pass) throws AccumuloSecurityException {
    if (!hasSystemPermission(credentials, credentials.user, SystemPermission.ALTER_USER) && !credentials.user.equals(user))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    // can't modify system user
    if (user.equals(SecurityConstants.SYSTEM_USERNAME))
      throw new AccumuloSecurityException(user, SecurityErrorCode.PERMISSION_DENIED);
    
    if (userExists(user)) {
      try {
        synchronized (zooCache) {
          zooCache.clear();
          ZooUtil.putPersistentData(ZKUserPath + "/" + user, Tool.createPass(pass), NodeExistsPolicy.OVERWRITE);
        }
        log.info("Changed password for user " + user + " at the request of user " + credentials.user);
      } catch (KeeperException e) {
        log.error(e, e);
        throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);
      } catch (InterruptedException e) {
        log.error(e, e);
        throw new RuntimeException(e);
      } catch (AccumuloException e) {
        log.error(e, e);
        throw new AccumuloSecurityException(user, SecurityErrorCode.DEFAULT_SECURITY_ERROR, e);
      }
    } else
      throw new AccumuloSecurityException(user, SecurityErrorCode.USER_DOESNT_EXIST); // user doesn't exist
  }
  
  /**
   * Checks if a user exists
   */
  private boolean userExists(String user) {
    return zooCache.get(ZKUserPath + "/" + user) != null;
  }
  
  public void changeAuthorizations(AuthInfo credentials, String user, Authorizations authorizations) throws AccumuloSecurityException {
    if (!hasSystemPermission(credentials, credentials.user, SystemPermission.ALTER_USER))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    // can't modify system user
    if (user.equals(SecurityConstants.SYSTEM_USERNAME))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    if (userExists(user)) {
      try {
        synchronized (zooCache) {
          zooCache.clear();
          ZooUtil.putPersistentData(ZKUserPath + "/" + user + ZKUserAuths, Tool.convertAuthorizations(authorizations), NodeExistsPolicy.OVERWRITE);
        }
        log.info("Changed authorizations for user " + user + " at the request of user " + credentials.user);
      } catch (KeeperException e) {
        log.error(e, e);
        throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);
      } catch (InterruptedException e) {
        log.error(e, e);
        throw new RuntimeException(e);
      }
    } else
      throw new AccumuloSecurityException(user, SecurityErrorCode.USER_DOESNT_EXIST); // user doesn't exist
  }
  
  public Authorizations getUserAuthorizations(AuthInfo credentials, String user) throws AccumuloSecurityException {
    if (!hasSystemPermission(credentials, credentials.user, SystemPermission.SYSTEM) && !credentials.user.equals(user))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    // system user doesn't need record-level authorizations for the tables it reads (for now)
    if (user.equals(SecurityConstants.SYSTEM_USERNAME))
      return Constants.NO_AUTHS;
    
    if (userExists(user))
      return Tool.convertAuthorizations(zooCache.get(ZKUserPath + "/" + user + ZKUserAuths));
    throw new AccumuloSecurityException(user, SecurityErrorCode.USER_DOESNT_EXIST); // user doesn't exist
  }
  
  /**
   * Checks if a user has a system permission
   * 
   * @return true if a user exists and has permission; false otherwise
   */
  @Override
  public boolean hasSystemPermission(AuthInfo credentials, String user, SystemPermission permission) throws AccumuloSecurityException {
    if (!authenticate(credentials))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.BAD_CREDENTIALS);
    
    // some people just aren't allowed to ask about other users; here are those who can ask
    if (!credentials.user.equals(user) && !hasSystemPermission(credentials, credentials.user, SystemPermission.SYSTEM)
        && !hasSystemPermission(credentials, credentials.user, SystemPermission.CREATE_USER)
        && !hasSystemPermission(credentials, credentials.user, SystemPermission.ALTER_USER)
        && !hasSystemPermission(credentials, credentials.user, SystemPermission.DROP_USER))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    if (user.equals(getRootUsername()) || user.equals(SecurityConstants.SYSTEM_USERNAME))
      return true;
    
    if (userExists(user))
      return Tool.convertSystemPermissions(zooCache.get(ZKUserPath + "/" + user + ZKUserSysPerms)).contains(permission);
    throw new AccumuloSecurityException(user, SecurityErrorCode.USER_DOESNT_EXIST); // user doesn't exist
  }
  
  @Override
  public boolean hasTablePermission(AuthInfo credentials, String user, String table, TablePermission permission) throws AccumuloSecurityException {
    if (!authenticate(credentials))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.BAD_CREDENTIALS);
    
    // some people just aren't allowed to ask about other users; here are those who can ask
    if (!credentials.user.equals(user) && !hasSystemPermission(credentials, credentials.user, SystemPermission.SYSTEM)
        && !hasSystemPermission(credentials, credentials.user, SystemPermission.CREATE_USER)
        && !hasSystemPermission(credentials, credentials.user, SystemPermission.ALTER_USER)
        && !hasSystemPermission(credentials, credentials.user, SystemPermission.DROP_USER))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    // always allow system user
    if (user.equals(SecurityConstants.SYSTEM_USERNAME))
      return true;
    
    // Don't let nonexistant users scan
    if (!userExists(user))
      throw new AccumuloSecurityException(user, SecurityErrorCode.USER_DOESNT_EXIST); // user doesn't exist
      
    // allow anybody to read the METADATA table
    if (table.equals(Constants.METADATA_TABLE_ID) && permission.equals(TablePermission.READ))
      return true;
    
    if (userTableExists(user, table)) {
      return Tool.convertTablePermissions(zooCache.get(ZKUserPath + "/" + user + ZKUserTablePerms + "/" + table)).contains(permission);
    }
    return false;
  }
  
  /**
   * Checks if a user has ANY permissions for a table. Assumes user exists
   */
  private boolean userTableExists(String user, String table) {
    return zooCache.get(ZKUserPath + "/" + user + ZKUserTablePerms + "/" + table) != null;
  }
  
  @Override
  public void grantSystemPermission(AuthInfo credentials, String user, SystemPermission permission) throws AccumuloSecurityException {
    if (!hasSystemPermission(credentials, credentials.user, SystemPermission.GRANT))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    // can't modify system user
    if (user.equals(SecurityConstants.SYSTEM_USERNAME))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    if (permission.equals(SystemPermission.GRANT))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.GRANT_INVALID);
    
    if (userExists(user)) {
      Set<SystemPermission> perms = Tool.convertSystemPermissions(zooCache.get(ZKUserPath + "/" + user + ZKUserSysPerms));
      try {
        if (perms.add(permission)) {
          synchronized (zooCache) {
            zooCache.clear();
            ZooUtil.putPersistentData(ZKUserPath + "/" + user + ZKUserSysPerms, Tool.convertSystemPermissions(perms), NodeExistsPolicy.OVERWRITE);
          }
        }
        log.info("Granted system permission " + permission + " for user " + user + " at the request of user " + credentials.user);
      } catch (KeeperException e) {
        log.error(e, e);
        throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);
      } catch (InterruptedException e) {
        log.error(e, e);
        throw new RuntimeException(e);
      }
    } else
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.USER_DOESNT_EXIST); // user doesn't exist
  }
  
  @Override
  public void grantTablePermission(AuthInfo credentials, String user, String table, TablePermission permission) throws AccumuloSecurityException {
    if (!hasSystemPermission(credentials, credentials.user, SystemPermission.ALTER_USER)
        && !hasTablePermission(credentials, credentials.user, table, TablePermission.GRANT))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    // can't modify system user
    if (user.equals(SecurityConstants.SYSTEM_USERNAME))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    if (userExists(user)) {
      Set<TablePermission> tablePerms;
      boolean hasTable = userTableExists(user, table);
      if (hasTable)
        tablePerms = Tool.convertTablePermissions(zooCache.get(ZKUserPath + "/" + user + ZKUserTablePerms + "/" + table));
      else
        tablePerms = new TreeSet<TablePermission>();
      
      try {
        if (tablePerms.add(permission)) {
          synchronized (zooCache) {
            zooCache.clear();
            ZooUtil.putPersistentData(ZKUserPath + "/" + user + ZKUserTablePerms + "/" + table, Tool.convertTablePermissions(tablePerms),
                NodeExistsPolicy.OVERWRITE);
          }
        }
        log.info("Granted table permission " + permission + " for user " + user + " on the table " + table + " at the request of user " + credentials.user);
      } catch (KeeperException e) {
        log.error(e, e);
        throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);
      } catch (InterruptedException e) {
        log.error(e, e);
        throw new RuntimeException(e);
      }
    } else
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.USER_DOESNT_EXIST); // user doesn't exist
  }
  
  @Override
  public void revokeSystemPermission(AuthInfo credentials, String user, SystemPermission permission) throws AccumuloSecurityException {
    if (!hasSystemPermission(credentials, credentials.user, SystemPermission.GRANT))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    // can't modify system user or revoke permissions from root user
    if (user.equals(SecurityConstants.SYSTEM_USERNAME) || user.equals(getRootUsername()))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    if (permission.equals(SystemPermission.GRANT))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.GRANT_INVALID);
    
    if (userExists(user)) {
      Set<SystemPermission> sysPerms = Tool.convertSystemPermissions(zooCache.get(ZKUserPath + "/" + user + ZKUserSysPerms));
      try {
        if (sysPerms.remove(permission)) {
          synchronized (zooCache) {
            zooCache.clear();
            ZooUtil.putPersistentData(ZKUserPath + "/" + user + ZKUserSysPerms, Tool.convertSystemPermissions(sysPerms), NodeExistsPolicy.OVERWRITE);
          }
        }
        log.info("Revoked system permission " + permission + " for user " + user + " at the request of user " + credentials.user);
      } catch (KeeperException e) {
        log.error(e, e);
        throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);
      } catch (InterruptedException e) {
        log.error(e, e);
        throw new RuntimeException(e);
      }
    } else
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.USER_DOESNT_EXIST);
  }
  
  @Override
  public void revokeTablePermission(AuthInfo credentials, String user, String table, TablePermission permission) throws AccumuloSecurityException {
    if (!hasSystemPermission(credentials, credentials.user, SystemPermission.ALTER_USER)
        && !hasTablePermission(credentials, credentials.user, table, TablePermission.GRANT))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    // can't modify system user
    if (user.equals(SecurityConstants.SYSTEM_USERNAME))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    if (userExists(user)) {
      if (!userTableExists(user, table))
        return;
      Set<TablePermission> tablePerms = Tool.convertTablePermissions(zooCache.get(ZKUserPath + "/" + user + ZKUserTablePerms + "/" + table));
      try {
        if (tablePerms.remove(permission)) {
          zooCache.clear();
          if (tablePerms.size() == 0)
            ZooUtil.recursiveDelete(ZKUserPath + "/" + user + ZKUserTablePerms + "/" + table, NodeMissingPolicy.SKIP);
          else
            ZooUtil.putPersistentData(ZKUserPath + "/" + user + ZKUserTablePerms + "/" + table, Tool.convertTablePermissions(tablePerms),
                NodeExistsPolicy.OVERWRITE);
        }
      } catch (KeeperException e) {
        log.error(e, e);
        throw new AccumuloSecurityException(user, SecurityErrorCode.CONNECTION_ERROR, e);
      } catch (InterruptedException e) {
        log.error(e, e);
        throw new RuntimeException(e);
      }
      log.info("Revoked table permission " + permission + " for user " + user + " on the table " + table + " at the request of user " + credentials.user);
    } else
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.USER_DOESNT_EXIST);
  }
  
  @Override
  public void deleteTable(AuthInfo credentials, String table) throws AccumuloSecurityException {
    if (!hasSystemPermission(credentials, credentials.user, SystemPermission.DROP_TABLE)
        && !hasTablePermission(credentials, credentials.user, table, TablePermission.DROP_TABLE))
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.PERMISSION_DENIED);
    
    try {
      synchronized (zooCache) {
        zooCache.clear();
        for (String user : zooCache.getChildren(ZKUserPath))
          ZooUtil.recursiveDelete(ZKUserPath + "/" + user + ZKUserTablePerms + "/" + table, NodeMissingPolicy.SKIP);
      }
    } catch (KeeperException e) {
      log.error(e, e);
      throw new AccumuloSecurityException(credentials.user, SecurityErrorCode.CONNECTION_ERROR, e);
    } catch (InterruptedException e) {
      log.error(e, e);
      throw new RuntimeException(e);
    }
  }
  
  /**
   * All the static too methods used for this class, so that we can separate out stuff that isn't using ZooKeeper. That way, we can check the synchronization
   * model more easily, as we only need to check to make sure zooCache is cleared when things are written to ZooKeeper in methods that might use it. These
   * won't, and so don't need to be checked.
   */
  static class Tool {
    private static final int SALT_LENGTH = 8;
    
    // Generates a byte array salt of length SALT_LENGTH
    private static byte[] generateSalt() {
      final SecureRandom random = new SecureRandom();
      byte[] salt = new byte[SALT_LENGTH];
      random.nextBytes(salt);
      return salt;
    }
    
    private static byte[] hash(byte[] raw) throws NoSuchAlgorithmException {
      MessageDigest md = MessageDigest.getInstance(Constants.PW_HASH_ALGORITHM);
      md.update(raw);
      return md.digest();
    }
    
    public static boolean checkPass(byte[] password, byte[] zkData) {
      byte[] salt = new byte[SALT_LENGTH];
      System.arraycopy(zkData, 0, salt, 0, SALT_LENGTH);
      byte[] passwordToCheck;
      try {
        passwordToCheck = convertPass(password, salt);
      } catch (NoSuchAlgorithmException e) {
        log.error("Count not create hashed password", e);
        return false;
      }
      return java.util.Arrays.equals(passwordToCheck, zkData);
    }
    
    public static byte[] createPass(byte[] password) throws AccumuloException {
      byte[] salt = generateSalt();
      try {
        return convertPass(password, salt);
      } catch (NoSuchAlgorithmException e) {
        log.error("Count not create hashed password", e);
        throw new AccumuloException("Count not create hashed password", e);
      }
    }
    
    private static byte[] convertPass(byte[] password, byte[] salt) throws NoSuchAlgorithmException {
      byte[] plainSalt = new byte[password.length + SALT_LENGTH];
      System.arraycopy(password, 0, plainSalt, 0, password.length);
      System.arraycopy(salt, 0, plainSalt, password.length, SALT_LENGTH);
      byte[] hashed = hash(plainSalt);
      byte[] saltedHash = new byte[SALT_LENGTH + hashed.length];
      System.arraycopy(salt, 0, saltedHash, 0, SALT_LENGTH);
      System.arraycopy(hashed, 0, saltedHash, SALT_LENGTH, hashed.length);
      return saltedHash; // contains salt+hash(password+salt)
    }
    
    public static Authorizations convertAuthorizations(byte[] authorizations) {
      return new Authorizations(authorizations);
    }
    
    public static byte[] convertAuthorizations(Authorizations authorizations) {
      return authorizations.getAuthorizationsArray();
    }
    
    public static byte[] convertSystemPermissions(Set<SystemPermission> systempermissions) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(systempermissions.size());
      DataOutputStream out = new DataOutputStream(bytes);
      try {
        for (SystemPermission sp : systempermissions)
          out.writeByte(sp.getId());
      } catch (IOException e) {
        log.error(e, e);
        throw new RuntimeException(e); // this is impossible with ByteArrayOutputStream; crash hard if this happens
      }
      return bytes.toByteArray();
    }
    
    public static Set<SystemPermission> convertSystemPermissions(byte[] systempermissions) {
      ByteArrayInputStream bytes = new ByteArrayInputStream(systempermissions);
      DataInputStream in = new DataInputStream(bytes);
      Set<SystemPermission> toReturn = new HashSet<SystemPermission>();
      try {
        while (in.available() > 0)
          toReturn.add(SystemPermission.getPermissionById(in.readByte()));
      } catch (IOException e) {
        log.error("User database is corrupt; error converting system permissions", e);
        toReturn.clear();
      }
      return toReturn;
    }
    
    public static byte[] convertTablePermissions(Set<TablePermission> tablepermissions) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(tablepermissions.size());
      DataOutputStream out = new DataOutputStream(bytes);
      try {
        for (TablePermission tp : tablepermissions)
          out.writeByte(tp.getId());
      } catch (IOException e) {
        log.error(e, e);
        throw new RuntimeException(e); // this is impossible with ByteArrayOutputStream; crash hard if this happens
      }
      return bytes.toByteArray();
    }
    
    public static Set<TablePermission> convertTablePermissions(byte[] tablepermissions) {
      Set<TablePermission> toReturn = new HashSet<TablePermission>();
      for (byte b : tablepermissions)
        toReturn.add(TablePermission.getPermissionById(b));
      return toReturn;
    }
  }
}
