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

import java.util.Base64;
import org.traccar.helper.ChannelBufferTools;

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
            "(\\d{0,}),"+//тип решение
            "(\\d{0,}),"+//количество используемых спутников
            "([\\d\\.]{0,}),"+//геометрический фактор, HDOP
            "(\\d{0,}\\.\\d+),"+//высота над уровнем моря в метрах
            ".+");

    Pattern patternPRKA = Pattern.compile(
            "\\$PRKA," +
            "(\\w{0,2}),"+ //Младший байт идентификатора путевой точки. HEX
            //"(\\d{0,2})(\\d{0,2})(\\d{0,2}),"+ //UTC дата по часам НТ. - бывает пустым
            "(\\d{0,2})(\\d{0,2})(\\d{0,2}),"+ //UTC дата по часам НТ.
            "(\\d{2})(\\d{2})(\\d{2})\\.?\\d*," + // Time                    
            "(\\w{0,}),"+//Событие
            "(\\w{0,})\\*"+//Состояние 
            ".+");
    
    Pattern patternPRKB = Pattern.compile("\\$PRKB,(\\d{0,}),([\\w=/+]{0,})\\*\\w{2}");
    
    Pattern patternPRKZ = Pattern.compile("\\$PRKZ,([\\w=/+]{0,})\\*\\w{2}");
    
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
                //channel.write("puts {$+}; puts {>> set BALANCE}; puts \"## [set BALANCE]\"; puts {$-}; lt");
                //channel.write(new byte[]{2,3});
                //channel.write("set BALANCE\r\n");
                //channel.write("puts $BALANCE\r\n");
                //channel.write("cfg get\r\n");
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
            
            Matcher parser = patternPRKA.matcher(sentence);
            if (!parser.matches()) {
                return null;
            }
            
            //$PRKA,8E,240913,083130,S,im*0A
            
            position = new Position();
            extendedInfo = new ExtendedInfoFormatter(getProtocol());
            
            position.setDeviceId(deviceId);
            position.setTableName(tableName);
            position.setImei(deviceImei);
            position.setCourse(0.0);
            position.setSpeed(0.0);
            position.setValid(false);
            position.setLatitude(0.0);
            position.setLongitude(0.0);
                        
            Integer index = 1;
            String m = parser.group(index++);
            
            // Date/Time
            String dd = parser.group(index++);
            String mm = parser.group(index++);
            String yy = parser.group(index++);
            
            // Time
            Calendar time = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
            time.clear();
            time.set(Calendar.YEAR, 2000 + Integer.valueOf("".equals(yy) ? "00" : yy));
            time.set(Calendar.MONTH, Integer.valueOf("".equals(mm) ? "1" : mm) - 1);
            time.set(Calendar.DAY_OF_MONTH, Integer.valueOf("".equals(dd) ? "1" : dd));
            time.set(Calendar.HOUR_OF_DAY, Integer.valueOf(parser.group(index++)));
            time.set(Calendar.MINUTE, Integer.valueOf(parser.group(index++)));
            time.set(Calendar.SECOND, Integer.valueOf(parser.group(index++)));
            position.setTime(time.getTime());
            //Событие
            extendedInfo.set("events", parser.group(index++));
            //Состояние
            extendedInfo.set("state", parser.group(index++));
            
            return null;
        }
        else if (sentence.startsWith("$PRKB")) {
            //Pattern patternPRKB = Pattern.compile("\\$PRKB,(\\d{1}),([a-zA-Z_0-9\\+\\=\\/]{0,})");
            
            //$PRKB,1,=EjMAkAAJAAAPiAzdABEAAAABK94AAAAUAAAArgDP//4=*3D
            Matcher parser = patternPRKB.matcher(sentence);
            if (!parser.matches()) {
                return null;
            }
            
            
        }
        //Конец
        else if (sentence.startsWith("$PRKZ")) {
           
            Matcher parser = patternPRKZ.matcher(sentence);
            if (!parser.matches()) {
                return null;
            }
            String str = parser.group(1);
            
            String[] data = str.split(",");
            
            int index=1;
            if(data.length > 0){//Слово статуса позиции. 
                extendedInfo.set("status", parser.group(index++));
            }
            if(data.length > 1){//Численный идентификатор геозоны, в которой находится объект.
                extendedInfo.set("zone", parser.group(index++));
            }
            if(data.length > 2){//Горизонтальная скорость относительно поверхности земли (м/сек). 
                //extendedInfo.set("speed", parser.group(index++));
                position.setSpeed(Double.parseDouble(parser.group(index++)) * 3.6);
            }
            if(data.length > 3){//Путевой угол (угл. градусы) 
                position.setCourse(Double.parseDouble(parser.group(index++)));
            }
            if(data.length > 4){//Значение идентификатора (например, RFID-метки) активного в данный момент аксессуара. 
                extendedInfo.set("rfid", parser.group(index++));
            }
            
        }
        //Номер logId точки в устройстве
        else if((sentence.charAt(0) == 2) && (sentence.charAt(sentence.length()-1) == 3)){
            //<02>233615<03>
            String logId = sentence.substring(1, sentence.length()-1);
            //extendedInfo.set("logId", logId);
            position.setLogId(Long.valueOf(logId));
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

            Integer index = 1;

            // Time
            if(position.getTime() == null){
                Calendar time = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                time.set(Calendar.HOUR_OF_DAY, Integer.valueOf(parser.group(index++)));
                time.set(Calendar.MINUTE, Integer.valueOf(parser.group(index++)));
                time.set(Calendar.SECOND, Integer.valueOf(parser.group(index++)));
                time.set(Calendar.MILLISECOND, 0);
                position.setTime(time.getTime());
            }
            else{
                index+=3;
            }
            // Validity
            //position.setValid(true);

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
            //position.setSpeed(0.0);
            
            // Course
            //position.setCourse(0.0);
            
           
            //"(\\d{0,}),"+//тип решение
            index++;                    
            //количество используемых спутников
            int satellites = Integer.valueOf(parser.group(index++));
            extendedInfo.set("satellites", satellites);   
            position.setValid(satellites > 3);
            
            //геометрический фактор, HDOP
            extendedInfo.set("hdop", parser.group(index++));
            // Altitude
            position.setAltitude(Double.parseDouble(parser.group(index++)));
        }
        
        return null;
    }

    private byte[] parseData(String data){
        if(data.charAt(0) == '='){
            byte[] decoded = Base64.getDecoder().decode(data.substring(1));
            return decoded;
        }
        else if(data.charAt(0) == '+' || data.charAt(0) == '-'){
            //Integer val = Integer.parseInt(data);
            //return val
        }
        else {
            return ChannelBufferTools.convertHexString(data);
        }
        return null;
    }
    
}
