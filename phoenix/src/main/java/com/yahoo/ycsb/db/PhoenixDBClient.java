/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you                                                                                                             
 * may not use this file except in compliance with the License. You                                                                                                                
 * may obtain a copy of the License at                                                                                                                                             
 *                                                                                                                                                                                 
 * http://www.apache.org/licenses/LICENSE-2.0                                                                                                                                      
 *                                                                                                                                                                                 
 * Unless required by applicable law or agreed to in writing, software                                                                                                             
 * distributed under the License is distributed on an "AS IS" BASIS,                                                                                                               
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or                                                                                                                 
 * implied. See the License for the specific language governing                                                                                                                    
 * permissions and limitations under the License. See accompanying                                                                                                                 
 * LICENSE file. 
 */

package com.yahoo.ycsb.db;

import com.yahoo.ycsb.DB;
import com.yahoo.ycsb.DBException;
import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.StringByteIterator;

import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.phoenix.jdbc.PhoenixDriver;

/**
 * This class extends {@link DB} and implements the database interface used
 * by the YCSB client for the Phoenix JDBC driver.
 * 
 * <br> Each client will have its own instance of this class. This client is
 * not thread safe.
 * 
 * <br> This interface expects a schema <key> <field1> <field2> <field3> ...
 * All attributes are of type VARCHAR. All accesses are through the primary
 * key. Therefore, only one index on the primary key is needed.
 * </ul>
 */

public class PhoenixDBClient extends DB implements PhoenixDBClientConstants {
	
  private Connection conn;
  private boolean initialized = false;
  private Properties props;
  private Integer jdbcFetchSize;
  private ConcurrentMap<StatementType, PreparedStatement> cachedStatements;

  /** The code to return when the call succeeds. */
  public static final int SUCCESS = 0;
  

  /**
   * The statement type for the prepared statements.
   */
  private static class StatementType {
    
    enum Type {
      INSERT(1),
      DELETE(2),
      READ(3),
      UPDATE(4),
      SCAN(5),
      ;
      int internalType;
      private Type(int type) {
        internalType = type;
      }
      
      int getHashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + internalType;
        return result;
      }
    }
    
    Type type;
    int numFields;
    String tableName;
    
    StatementType(Type type, String tableName, int numFields) {
      this.type = type;
      this.tableName = tableName;
      this.numFields = numFields;
    }

    @Override
    public int hashCode() {
      final int prime = 31;
      int result = 1;
      result = prime * result + numFields;
      result = prime * result
          + ((tableName == null) ? 0 : tableName.hashCode());
      result = prime * result + ((type == null) ? 0 : type.getHashCode());
      return result;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      StatementType other = (StatementType) obj;
      if (numFields != other.numFields)
        return false;
      if (tableName == null) {
        if (other.tableName != null)
          return false;
      } else if (!tableName.equals(other.tableName))
        return false;
      if (type != other.type)
        return false;
      return true;
    }
  }

  /**
   * Initialize the database connection and set it up for sending requests to the database.
   * This must be called once per client.
   * @throws  
   */
  @Override
	public void init() throws DBException {
		if (initialized) {
		  System.err.println("Client connection already initialized.");
		  return;
		}

		props = getProperties();
    String driver = props.getProperty(DRIVER_CLASS, PhoenixDriver.class.getCanonicalName());
		String url = props.getProperty(CONNECTION_URL);
		if (url == null) {
		  throw new DBException("Connection URL must be specified but is missing");
		}
		try {
		  if (driver != null) {
	      Class.forName(driver);
	    }
		  Connection conn = DriverManager.getConnection(url);
		  // Since there is no explicit commit method in the YCSB DB interface all
		  // operations should auto commit
		  conn.setAutoCommit(true);
		} catch (ClassNotFoundException e) {
		  System.err.println("Error in initializing the driver: " + e);
		  throw new DBException(e);
		} catch (SQLException e) {
		  System.err.println("Error in database operation: " + e);
      throw new DBException(e);
    } catch (NumberFormatException e) {
      System.err.println("Invalid value for fieldcount property. " + e);
      throw new DBException(e);
    }

    cachedStatements = new ConcurrentHashMap<StatementType, PreparedStatement>();

		initialized = true;
	}
	
  @Override
	public void cleanup() throws DBException {
	  try {
	    conn.close();
    } catch (SQLException e) {
      System.err.println("Error in closing the connection. " + e);
      throw new DBException(e);
    }
	}
	
	private PreparedStatement createAndCacheInsertStatement(StatementType insertType, String key)
	throws SQLException {
	  StringBuilder insert = new StringBuilder("INSERT INTO ");
	  insert.append(insertType.tableName);
	  insert.append(" VALUES(?");
    for (int i = 0; i < insertType.numFields; i++) {
      insert.append(",?");
    }
    insert.append(");");
    PreparedStatement insertStatement = conn.prepareStatement(insert.toString());
    PreparedStatement stmt = cachedStatements.putIfAbsent(insertType, insertStatement);
    if (stmt == null) return insertStatement;
    else return stmt;
	}
	
	private PreparedStatement createAndCacheReadStatement(StatementType readType, String key)
	throws SQLException {
    StringBuilder read = new StringBuilder("SELECT * FROM ");
    read.append(readType.tableName);
    read.append(" WHERE ");
    read.append(PRIMARY_KEY);
    read.append(" = ");
    read.append("?;");
    PreparedStatement readStatement = conn.prepareStatement(read.toString());
    PreparedStatement stmt = cachedStatements.putIfAbsent(readType, readStatement);
    if (stmt == null) return readStatement;
    else return stmt;
  }
	
	private PreparedStatement createAndCacheDeleteStatement(StatementType deleteType, String key)
	throws SQLException {
    StringBuilder delete = new StringBuilder("DELETE FROM ");
    delete.append(deleteType.tableName);
    delete.append(" WHERE ");
    delete.append(PRIMARY_KEY);
    delete.append(" = ?;");
    PreparedStatement deleteStatement = conn.prepareStatement(delete.toString());
    PreparedStatement stmt = cachedStatements.putIfAbsent(deleteType, deleteStatement);
    if (stmt == null) return deleteStatement;
    else return stmt;
  }
	
	private PreparedStatement createAndCacheUpdateStatement(StatementType updateType, String key)
	throws SQLException {
    StringBuilder update = new StringBuilder("UPDATE ");
    update.append(updateType.tableName);
    update.append(" SET ");
    for (int i = 1; i <= updateType.numFields; i++) {
      update.append(COLUMN_PREFIX);
      update.append(i);
      update.append("=?");
      if (i < updateType.numFields) update.append(", ");
    }
    update.append(" WHERE ");
    update.append(PRIMARY_KEY);
    update.append(" = ?;");
    PreparedStatement insertStatement = conn.prepareStatement(update.toString());
    PreparedStatement stmt = cachedStatements.putIfAbsent(updateType, insertStatement);
    if (stmt == null) return insertStatement;
    else return stmt;
  }
	
	private PreparedStatement createAndCacheScanStatement(StatementType scanType, String key)
	throws SQLException {
	  StringBuilder select = new StringBuilder("SELECT * FROM ");
    select.append(scanType.tableName);
    select.append(" WHERE ");
    select.append(PRIMARY_KEY);
    select.append(" >= ");
    select.append("?;");
    PreparedStatement scanStatement = conn.prepareStatement(select.toString());
    if (this.jdbcFetchSize != null) scanStatement.setFetchSize(this.jdbcFetchSize);
    PreparedStatement stmt = cachedStatements.putIfAbsent(scanType, scanStatement);
    if (stmt == null) return scanStatement;
    else return stmt;
  }

	@Override
	public int read(String tableName, String key, Set<String> fields,
			HashMap<String, ByteIterator> result) {
	  if (tableName == null) {
      return -1;
    }
    if (key == null) {
      return -1;
    }
    try {
      StatementType type = new StatementType(StatementType.Type.READ, tableName, 1);
      PreparedStatement readStatement = cachedStatements.get(type);
      if (readStatement == null) {
        readStatement = createAndCacheReadStatement(type, key);
      }
      readStatement.setString(1, key);
      ResultSet resultSet = readStatement.executeQuery();
      if (!resultSet.next()) {
        resultSet.close();
        return 1;
      }
      if (result != null && fields != null) {
        for (String field : fields) {
          String value = resultSet.getString(field);
          result.put(field, new StringByteIterator(value));
        }
      }
      resultSet.close();
      return SUCCESS;
    } catch (SQLException e) {
        System.err.println("Error in processing read of table " + tableName + ": "+e);
      return -2;
    }
	}

	@Override
	public int scan(String tableName, String startKey, int recordcount,
			Set<String> fields, Vector<HashMap<String, ByteIterator>> result) {
	  if (tableName == null) {
      return -1;
    }
    if (startKey == null) {
      return -1;
    }
    try {
      StatementType type = new StatementType(StatementType.Type.SCAN, tableName, 1);
      PreparedStatement scanStatement = cachedStatements.get(type);
      if (scanStatement == null) {
        scanStatement = createAndCacheScanStatement(type, startKey);
      }
      scanStatement.setString(1, startKey);
      ResultSet resultSet = scanStatement.executeQuery();
      for (int i = 0; i < recordcount && resultSet.next(); i++) {
        if (result != null && fields != null) {
          HashMap<String, ByteIterator> values = new HashMap<String, ByteIterator>();
          for (String field : fields) {
            String value = resultSet.getString(field);
            values.put(field, new StringByteIterator(value));
          }
          result.add(values);
        }
      }
      resultSet.close();
      return SUCCESS;
    } catch (SQLException e) {
      System.err.println("Error in processing scan of table: " + tableName + e);
      return -2;
    }
	}

	@Override
	public int update(String tableName, String key, HashMap<String, ByteIterator> values) {
	  if (tableName == null) {
      return -1;
    }
    if (key == null) {
      return -1;
    }
    try {
      int numFields = values.size();
      StatementType type = new StatementType(StatementType.Type.UPDATE, tableName, numFields);
      PreparedStatement updateStatement = cachedStatements.get(type);
      if (updateStatement == null) {
        updateStatement = createAndCacheUpdateStatement(type, key);
      }
      int index = 1;
      for (Map.Entry<String, ByteIterator> entry : values.entrySet()) {
        updateStatement.setString(index++, entry.getValue().toString());
      }
      updateStatement.setString(index, key);
      int result = updateStatement.executeUpdate();
      if (result == 1) return SUCCESS;
      else return 1;
    } catch (SQLException e) {
      System.err.println("Error in processing update to table: " + tableName + e);
      return -1;
    }
	}

	@Override
	public int insert(String tableName, String key, HashMap<String, ByteIterator> values) {
	  if (tableName == null) {
	    return -1;
	  }
	  if (key == null) {
	    return -1;
	  }
	  try {
	    int numFields = values.size();
	    StatementType type = new StatementType(StatementType.Type.INSERT, tableName, numFields);
	    PreparedStatement insertStatement = cachedStatements.get(type);
	    if (insertStatement == null) {
	      insertStatement = createAndCacheInsertStatement(type, key);
	    }
      insertStatement.setString(1, key);
      int index = 2;
      for (Map.Entry<String, ByteIterator> entry : values.entrySet()) {
        String field = entry.getValue().toString();
        insertStatement.setString(index++, field);
      }
      int result = insertStatement.executeUpdate();
      if (result == 1) return SUCCESS;
      else return 1;
    } catch (SQLException e) {
      System.err.println("Error in processing insert to table: " + tableName + e);
      return -1;
    }
	}

	@Override
	public int delete(String tableName, String key) {
	  if (tableName == null) {
      return -1;
    }
    if (key == null) {
      return -1;
    }
    try {
      StatementType type = new StatementType(StatementType.Type.DELETE, tableName, 1);
      PreparedStatement deleteStatement = cachedStatements.get(type);
      if (deleteStatement == null) {
        deleteStatement = createAndCacheDeleteStatement(type, key);
      }
      deleteStatement.setString(1, key);
      int result = deleteStatement.executeUpdate();
      if (result == 1) return SUCCESS;
      else return 1;
    } catch (SQLException e) {
      System.err.println("Error in processing delete to table: " + tableName + e);
      return -1;
    }
	}
}
