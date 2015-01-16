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

import com.mchange.v2.c3p0.ComboPooledDataSource;
import java.io.File;
import java.io.StringReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.traccar.helper.DriverDelegate;
import org.traccar.helper.Log;
import org.traccar.model.Device;
import org.traccar.model.DeviceCommand;
import org.traccar.model.Position;
import org.xml.sax.InputSource;

/**
 * Database abstraction class
 */
public class DataManager {

    public DataManager(Properties properties) throws Exception {
        if (properties != null) {
            initDatabase(properties);
            
            // Refresh delay
            String refreshDelay = properties.getProperty("database.refreshDelay");
            if (refreshDelay != null) {
                devicesRefreshDelay = Long.valueOf(refreshDelay) * 1000;
            } else {
                devicesRefreshDelay = DEFAULT_REFRESH_DELAY * 1000;
            }
        }
    }
    
    private DataSource dataSource;
    
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * Database statements
     */
    private NamedParameterStatement queryGetDevices;
    private NamedParameterStatement queryAddPosition;
    private NamedParameterStatement queryUpdateLatestPosition;
    
    private NamedParameterStatement queryGetCommands;
    private NamedParameterStatement queryAddCommand;
    private NamedParameterStatement queryUpdateCommand;
    
    private String infoStyle;

    /**
     * Initialize database
     */
    private void initDatabase(Properties properties) throws Exception {

        // Load driver
        String driver = properties.getProperty("database.driver");
        if (driver != null) {
            String driverFile = properties.getProperty("database.driverFile");

            if (driverFile != null) {
                URL url = new URL("jar:file:" + new File(driverFile).getAbsolutePath() + "!/");
                URLClassLoader cl = new URLClassLoader(new URL[]{url});
                Driver d = (Driver) Class.forName(driver, true, cl).newInstance();
                DriverManager.registerDriver(new DriverDelegate(d));
            } else {
                Class.forName(driver);
            }
        }
        
        // Initialize data source
        ComboPooledDataSource ds = new ComboPooledDataSource();
        ds.setDriverClass(properties.getProperty("database.driver"));
        ds.setJdbcUrl(properties.getProperty("database.url"));
        ds.setUser(properties.getProperty("database.user"));
        ds.setPassword(properties.getProperty("database.password"));
        ds.setIdleConnectionTestPeriod(600);
        ds.setTestConnectionOnCheckin(true);
        dataSource = ds;

        // Load statements from configuration
        String query;

        query = properties.getProperty("database.selectDevice");
        if (query != null) {
            queryGetDevices = new NamedParameterStatement(query, dataSource);
        }

        query = properties.getProperty("database.insertPosition");
        if (query != null) {
            queryAddPosition = new NamedParameterStatement(query, dataSource, Statement.RETURN_GENERATED_KEYS);
        }

        query = properties.getProperty("database.updateLatestPosition");
        if (query != null) {
            queryUpdateLatestPosition = new NamedParameterStatement(query, dataSource);
        }
        
        // Commands
        query = properties.getProperty("database.selectCommand");
        if (query != null) {
            queryGetCommands = new NamedParameterStatement(query, dataSource);
        }
        query = properties.getProperty("database.insertCommand");
        if (query != null) {
            //queryAddCommand = new NamedParameterStatement(query, dataSource);
            queryAddCommand = new NamedParameterStatement(query, dataSource, Statement.RETURN_GENERATED_KEYS);
        }
        query = properties.getProperty("database.updateCommand");
        if (query != null) {
            queryUpdateCommand = new NamedParameterStatement(query, dataSource);
        }
        
        //Initialization info style
        infoStyle = properties.getProperty("database.infoStyle");
        if (infoStyle == null) {
            infoStyle="xml";
        }       
    }

    private final NamedParameterStatement.ResultSetProcessor<Device> deviceResultSetProcessor = new NamedParameterStatement.ResultSetProcessor<Device>() {
        @Override
        public Device processNextRow(ResultSet rs) throws SQLException {
            Device device = new Device();
            device.setId(rs.getLong("id"));
            device.setImei(rs.getString("imei"));
            
            //Уникальный ID - не использую       
            try{
                device.setUniqueId(rs.getString("uniqueid"));
            } catch (SQLException e) {
                
            }
            //Имя таблицы для вставки/обновления позиции
            try{
                device.setTableName(rs.getString("tablename"));
            } catch (SQLException e) {
                device.setTableName("");
            }
            //
            return device;
        }
    };

    public List<Device> getDevices() throws SQLException {
        if (queryGetDevices != null) {
            return queryGetDevices.prepare().executeQuery(deviceResultSetProcessor);
        } else {
            return new LinkedList<Device>();
        }
    }
    
    
    private final NamedParameterStatement.ResultSetProcessor<DeviceCommand> commandResultSetProcessor = new NamedParameterStatement.ResultSetProcessor<DeviceCommand>() {
        @Override
        public DeviceCommand processNextRow(ResultSet rs) throws SQLException {
            DeviceCommand command = new DeviceCommand();
            command.setId(rs.getLong("id"));
            command.setDeviceId(rs.getLong("device_id"));
            command.setImei(rs.getString("imei"));
            command.setCommand(rs.getString("command"));         
            try{
                command.setData(rs.getString("data")); 
            } catch (SQLException e) {
                
            }
            return command;
        }
    };
    public List<DeviceCommand> getCommands() throws SQLException {
        if (queryGetCommands != null) {
            return queryGetCommands.prepare().executeQuery(commandResultSetProcessor);
        } else {
            return new LinkedList<DeviceCommand>();
        }
    }
    
    public List<DeviceCommand> getCommandsByImei(String imei){
        if(commands == null){ return null; }
        if(commands.get(imei) == null){ return null; }
        if(commands.get(imei).isEmpty()){ return null; }
        return commands.get(imei);
    }

    public String getStyleInfo(){
        return infoStyle;
    }
    
    /**
     * Devices cache
     */
    private Map<String, Device> devices;
    /**
     * Devices commands cache
     */
    private Map<String, List<DeviceCommand>> commands;
    private Calendar devicesLastUpdate;
    private long devicesRefreshDelay;
    private static final long DEFAULT_REFRESH_DELAY = 300;

    public Device getDeviceByImei(String imei) throws SQLException {

        if (devices == null || !devices.containsKey(imei) ||
                (Calendar.getInstance().getTimeInMillis() - devicesLastUpdate.getTimeInMillis() > devicesRefreshDelay)) {
            devices = new HashMap<String, Device>();
            for (Device device : getDevices()) {
                devices.put(device.getImei(), device);
            }
            
            commands = new HashMap<String, List<DeviceCommand>>();
            for (DeviceCommand command : getCommands()) {
                List<DeviceCommand> deviceCommands;
                if(!commands.containsKey(command.getImei())){
                    commands.put(command.getImei(),new LinkedList<DeviceCommand>());
                }
                deviceCommands = commands.get(command.getImei());
                deviceCommands.add(command);
            }            
            devicesLastUpdate = Calendar.getInstance();
        }

        return devices.get(imei);
    }

    private NamedParameterStatement.ResultSetProcessor<Long> generatedKeysResultSetProcessor = new NamedParameterStatement.ResultSetProcessor<Long>() {
        @Override
        public Long processNextRow(ResultSet rs) throws SQLException {
            return rs.getLong(1);
        }
    };

    public synchronized Long addPosition(Position position) throws SQLException {
        if (queryAddPosition != null) {            
            
            List<Long> result = assignVariables(queryAddPosition.prepare(), position).executeUpdate(generatedKeysResultSetProcessor);
            if (result != null && !result.isEmpty()) {
                return result.iterator().next();
            }
        }
        return null;
    }

    public synchronized Long addCommand(DeviceCommand command) throws SQLException {
        if (queryAddCommand != null && (command.getId()==0)) {
            List<Long> result = assignCommandVariables(queryAddCommand.prepare(), command).executeUpdate(generatedKeysResultSetProcessor);
            if (result != null && !result.isEmpty()) {
                return result.iterator().next();
            }
        }else if(queryUpdateCommand != null && (command.getId()>0)){
            assignCommandVariables(queryUpdateCommand.prepare(), command).executeUpdate();
        }
        return null;
    }
    
    public void updateLatestPosition(Position position, Long positionId) throws SQLException {
        if (queryUpdateLatestPosition != null) {
            assignVariables(queryUpdateLatestPosition.prepare(), position).setLong("id", positionId).executeUpdate();
        }
    }

    private NamedParameterStatement.Params assignVariables(NamedParameterStatement.Params params, Position position) throws SQLException {
       
        params.setLong("device_id", position.getDeviceId());
        params.setTimestamp("time", position.getTime());
        params.setBoolean("valid", position.getValid());
        params.setDouble("altitude", position.getAltitude());
        params.setDouble("latitude", position.getLatitude());
        params.setDouble("longitude", position.getLongitude());
        params.setDouble("speed", position.getSpeed());
        params.setDouble("course", position.getCourse());
        params.setString("address", position.getAddress());
        params.setString("extended_info", position.getExtendedInfo());  
        
        params.setString("imei", position.getImei());
        params.setTimestamp("systemtime", position.getServerTime());
        
        params.setTableName(position.getTableName());
        
        if(this.getStyleInfo().equals("xml")){

            // DELME: Temporary compatibility support
            XPath xpath = XPathFactory.newInstance().newXPath();
            try {
                InputSource source = new InputSource(new StringReader(position.getExtendedInfo()));
                String index = xpath.evaluate("/info/index", source);
                if (!index.isEmpty()) {
                    params.setLong("id", Long.valueOf(index));
                } else {
                    params.setLong("id", null);
                }
                source = new InputSource(new StringReader(position.getExtendedInfo()));
                String power = xpath.evaluate("/info/power", source);
                if (!power.isEmpty()) {
                    params.setDouble("power", Double.valueOf(power));
                } else {
                    params.setLong("power", null);
                }
            } catch (XPathExpressionException e) {
                Log.warning("Error in XML: " + position.getExtendedInfo(), e);
                params.setLong("id", null);
                params.setLong("power", null);
            }
        }
        else if(this.getStyleInfo().equals("quant")){
            String info = position.getExtendedInfo();
            try{
                int start = info.indexOf("index=");
                if(start>=0){
                    String index = info.substring(start+6, info.indexOf(";",start+6));
                    params.setLong("id", Long.valueOf(index));
                }
                else{
                    params.setLong("id", null);
                }
            }
            catch(Exception e){
                Log.warning("Error in parse ExtendedInfo: " + position.getExtendedInfo(), e);
                params.setLong("id", null);
            }
            
            try{
                int start = info.indexOf("power=");
                if(start>=0){
                    String power = info.substring(start+6, info.indexOf(";",start+6));
                    params.setLong("power", Long.valueOf(power));
                }
                else{
                    params.setLong("power", null);
                }
            }
            catch(Exception e){
                Log.warning("Error in parse ExtendedInfo: " + position.getExtendedInfo(), e);
                params.setLong("power", null);
            }
        }
        
        return params;
    }
    
    public String getQuantParametr(String info, String paramName){
        String paramValue=null;
        try{
            int start = info.indexOf(paramName+"=");
            if(start>=0){
                int count = paramName.length()+1;
                String index = info.substring(start+count, info.indexOf(";",start+count));
                paramValue = index;
            }
            else{
                paramValue = null;
            }
        }
        catch(Exception e){
            Log.warning("Error in parse getQuantParametr: " + info, e);
            paramValue = null;
        }
        return paramValue;
    }

    private NamedParameterStatement.Params assignCommandVariables(NamedParameterStatement.Params params, DeviceCommand command) throws SQLException {
       
        params.setLong("id", command.getId());
        params.setLong("device_id", command.getDeviceId());
        params.setTimestamp("time", command.getCommandTime());
        params.setString("command", command.getCommand());
        params.setString("imei", command.getImei());
        params.setString("data", command.getData());      
        return params;
    }
}
