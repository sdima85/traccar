/*
 * Copyright 2012 - 2014 Anton Tananaev (anton.tananaev@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.database;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.Date;

public class NamedParameterStatement {

    private final Map<String, List<Integer>> indexMap;

    private final String parsedQuery;

    private final DataSource dataSource;

    private final int autoGeneratedKeys;

    public interface ResultSetProcessor<T> {
        T processNextRow(ResultSet rs) throws SQLException;
    }

    public class Params {
        Map<String, Object> values;
        Map<String, Integer> types;
        
        String tableName;

        public Params setInt(String name, Integer value) {
            setType(name, Types.INTEGER);
            return setValue(name, value);
        }

        public Params setLong(String name, Long value) {
            setType(name, Types.INTEGER);
            return setValue(name, value);
        }

        public Params setString(String name, String value) {
            setType(name, Types.VARCHAR);
            return setValue(name, value);
        }

        public Params setDouble(String name, Double value) {
            setType(name, Types.DOUBLE);
            return setValue(name, value);
        }

        public Params setTimestamp(String name, Date value) {
            setType(name, Types.TIMESTAMP);
            return setValue(name, value);
        }

        public Params setBoolean(String name, Boolean value) {
            setType(name, Types.BOOLEAN);
            return setValue(name, value);
        }

        private void setType(String name, Integer type) {
            if (types == null) {
                types = new HashMap<String, Integer>();
            }
            types.put(name, type);
        }

        private Params setValue(String name, Object value) {
            if (values == null) {
                values = new HashMap<String, Object>();
            }
            values.put(name, value);
            return this;
        }

        public void setTableName(String tableName){
            //if((dataBase!==null) && (!dataBase.isEmpty())){
                this.tableName=tableName;
            //}
            //else{
            //    this.dataBase="";
            //}
        }
        
        public <T> List<T> executeQuery(ResultSetProcessor<T> processor) throws SQLException {
            Connection conn = null;
            PreparedStatement stmt = null;
            ResultSet rs = null;

            List<T> result = new LinkedList<T>();

            try {
                conn = dataSource.getConnection();
                stmt = conn.prepareStatement(parsedQuery);

                setParams(stmt);

                rs = stmt.executeQuery();
                while (rs.next()) {
                    result.add(processor.processNextRow(rs));
                }
            } finally {
                closeQuietly(conn, stmt, rs);
            }

            return result;
        }

        public <T> List<T> executeUpdate(ResultSetProcessor<T> processor) throws SQLException {
            Connection conn = null;
            PreparedStatement stmt = null;
            ResultSet rs = null;

            try {
                String sql = parsedQuery;
                if(tableName!=null){
                    sql = sql.replace("*tablename", tableName);
                }
                conn = dataSource.getConnection();
                stmt = conn.prepareStatement(sql, autoGeneratedKeys);

                setParams(stmt);
                stmt.executeUpdate();
                
                if (autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS) {
                    rs = stmt.getGeneratedKeys();
                    List<T> result = new LinkedList<T>();
                    while (rs.next()) {
                        result.add(processor.processNextRow(rs));
                    }
                    return result;
                }
            } finally {
                closeQuietly(conn, stmt, rs);
            }

            return null;
        }

        public void executeUpdate() throws SQLException {
            executeUpdate(null);
        }

        private void setParams(PreparedStatement stmt) throws SQLException {
            if (values == null) {
                return;
            }

            for (Map.Entry<String, Object> entry : values.entrySet()) {
                String name = entry.getKey();
                Object value = entry.getValue();
                int type = types.get(name);

                List<Integer> indexList = indexMap.get(name);
                if (indexList != null) for (Integer index: indexList) {
                    if (value == null) {
                        stmt.setNull(index, type);
                    } else {
                        switch (type) {
                            case Types.INTEGER:
                                if (value instanceof Long) {
                                    stmt.setLong(index, (Long) value);
                                } else if (value instanceof Integer) {
                                    stmt.setInt(index, (Integer) value);
                                } else {
                                    throw new IllegalArgumentException("Unknown integer value: " + value.getClass());
                                }
                                break;
                            case Types.VARCHAR:
                                stmt.setString(index, (String) value);
                                break;
                            case Types.TIMESTAMP:
                                stmt.setTimestamp(index, new Timestamp(((Date) value).getTime()));
                                break;
                            case Types.DOUBLE:
                                stmt.setDouble(index, (Double) value);
                                break;
                            case Types.BOOLEAN:
                                stmt.setBoolean(index, (Boolean) value);
                                break;
                        }
                    }
                }
            }
        }

        private void closeQuietly(Connection conn, Statement stmt, ResultSet rs) {
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException sqex) {
                }
            }

            if (stmt != null) {
                try {
                    stmt.close();
                } catch (SQLException sqex) {
                }
            }

            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException sqex) {
                }
            }
        }
    }

    public NamedParameterStatement(String query, DataSource dataSource) {
        this(query, dataSource, Statement.NO_GENERATED_KEYS);
    }

    public NamedParameterStatement(String query, DataSource dataSource, int autoGeneratedKeys) {
        this.indexMap = new HashMap<String, List<Integer>>();
        this.parsedQuery = parse(query, indexMap);
        this.dataSource = dataSource;
        this.autoGeneratedKeys = autoGeneratedKeys;
    }

    static String parse(String query, Map<String, List<Integer>> paramMap) {

        int length = query.length();
        StringBuilder parsedQuery = new StringBuilder(length);
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        int index = 1;

        for(int i = 0; i < length; i++) {

            char c = query.charAt(i);

            // String end
            if (inSingleQuote) {
                if (c == '\'') inSingleQuote = false;
            } else if (inDoubleQuote) {
                if (c == '"') inDoubleQuote = false;
            } else {

                // String begin
                if (c == '\'') {
                    inSingleQuote = true;
                } else if (c == '"') {
                    inDoubleQuote = true;
                } else if (c == ':' && i + 1 < length &&
                        Character.isJavaIdentifierStart(query.charAt(i + 1))) {

                    // Identifier name
                    int j = i + 2;
                    while (j < length && Character.isJavaIdentifierPart(query.charAt(j))) j++;

                    String name = query.substring(i + 1, j);
                    c = '?';
                    i += name.length();

                    // Add to list
                    List<Integer> indexList = paramMap.get(name);
                    if (indexList == null) {
                        indexList = new LinkedList<Integer>();
                        paramMap.put(name, indexList);
                    }
                    indexList.add(index);

                    index++;
                }
            }

            parsedQuery.append(c);
        }

        return parsedQuery.toString();
    }

    public Params prepare() {
        return new Params();
    }
}
