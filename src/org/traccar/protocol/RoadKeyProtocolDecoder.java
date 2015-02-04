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
package org.traccar.protocol;

import java.util.Calendar;
import java.util.Properties;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;

import org.traccar.BaseProtocolDecoder;
import org.traccar.database.DataManager;
import org.traccar.helper.Log;
import org.traccar.model.Device;
import org.traccar.model.ExtendedInfoFormatter;
import org.traccar.model.Position;

public class RoadKeyProtocolDecoder extends BaseProtocolDecoder {

    private Long deviceId;
    private String deviceImei;
    private String tableName;
    
    private Position position;
    private ExtendedInfoFormatter extendedInfo;

    public RoadKeyProtocolDecoder(DataManager dataManager, String protocol, Properties properties) {
        super(dataManager, protocol, properties);
    }

    private static final Pattern patternGPGGA = Pattern.compile(
            "\\$GPGGA," +
            "(\\d{2})(\\d{2})(\\d{2})\\.?\\d*," + // Time
            "(\\d{2})(\\d{2}\\.\\d+)," +   // Latitude
            "([NS])," +
            "(\\d{3})(\\d{2}\\.\\d+)," +   // Longitude
            "([EW])," +
            ".+");

    private void identify(String id) {
        try {
            Device device = getDataManager().getDeviceByImei(id);
            deviceImei = id;
            deviceId = device.getId();
            tableName = device.getTableName();
            
        } catch(Exception error) {
            Log.warning("Unknown device - " + id);
        }
    }
    
    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, Object msg)
            throws Exception {

        String sentence = (String) msg;
        
        //Посылка строки приветствия >>>>>>
        if (sentence.startsWith("!NM")) {
            //!NM311-06080801023 Nadiya 1.9c.4012 (c) Road Key
            if (channel != null) {
                channel.write("set IMEI\r\n");
            }
            return null;
        }
        //<02>353976014438992<03>
        if((sentence.length() == 17) && (sentence.charAt(0) == 2) && (sentence.charAt(16) == 3)){
            identify(sentence.substring(1, 15));            
            if (channel != null) {
                channel.write("lt\r\n");
            }
            return null;
        }      
        //Ошибка чтения путевого блока
        if (sentence.startsWith("$PRKERR")) {
            //Очистка
            position = null;
            extendedInfo = null;
        }
        //Старт - 
        if (sentence.startsWith("$PRKA")) {
            //$PRKA,8E,240913,083130,S,im*0A
            position = new Position();
            extendedInfo = new ExtendedInfoFormatter(getProtocol());
            
            position.setDeviceId(deviceId);
            position.setTableName(tableName);
            position.setImei(deviceImei);
            
            
            
            return null;
        }
        else if (sentence.startsWith("$PRKB")) {
            //$PRKB,1,=EjMAkAAJAAAPiAzdABEAAAABK94AAAAUAAAArgDP//4=*3D
            
        }
        //Конец
        else if (sentence.startsWith("$PRKZ")) {
            //$PRKZ,S*6C
            
        }
        //Номер logId точки в устройстве
        else if((sentence.charAt(0) == 2) && (sentence.charAt(sentence.length()-1) == 3)){
            //<02>233615<03>
            String logId = sentence.substring(1, sentence.length()-1);
            extendedInfo.set("logId", logId);
            if (channel != null) {
                channel.write("lt\r\n");
            }
            //Отправка в базу            
            position.setExtendedInfo(extendedInfo.getStyle(getDataManager().getStyleInfo()));
            return position;
        }
        // Location
        else if (sentence.startsWith("$GPGGA") && deviceId != null) {
//$GPGGA,083130,4849.266,N,03301.323,E,1,5,3.6,135.3,M*2C
            // Parse message
            Matcher parser = patternGPGGA.matcher(sentence);
            if (!parser.matches()) {
                return null;
            }

            // Create new position
            //Position position = new Position();
            //ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
            //position.setDeviceId(deviceId);
            //position.setTableName(tableName);
            //position.setImei(deviceImei);

            Integer index = 1;

            // Time
            Calendar time = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            time.set(Calendar.HOUR_OF_DAY, Integer.valueOf(parser.group(index++)));
            time.set(Calendar.MINUTE, Integer.valueOf(parser.group(index++)));
            time.set(Calendar.SECOND, Integer.valueOf(parser.group(index++)));
            time.set(Calendar.MILLISECOND, 0);
            position.setTime(time.getTime());

            // Validity
            position.setValid(true);

            // Latitude
            Double latitude = Double.valueOf(parser.group(index++));
            latitude += Double.valueOf(parser.group(index++)) / 60;
            if (parser.group(index++).compareTo("S") == 0) latitude = -latitude;
            position.setLatitude(latitude);

            // Longitude
            Double longitude = Double.valueOf(parser.group(index++));
            longitude += Double.valueOf(parser.group(index++)) / 60;
            if (parser.group(index++).compareTo("W") == 0) longitude = -longitude;
            position.setLongitude(longitude);
            
            // Speed
            position.setSpeed(0.0);
            
            // Course
            position.setCourse(0.0);

            // Altitude
            position.setAltitude(0.0);

            //position.setExtendedInfo(extendedInfo.getStyle(getDataManager().getStyleInfo()));
            //return position;
        }
        
        return null;
    }

}
