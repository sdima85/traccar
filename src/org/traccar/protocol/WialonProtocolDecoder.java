/*
 * Copyright 2013 - 2014 Anton Tananaev (anton.tananaev@gmail.com)
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
import java.util.LinkedList;
import java.util.List;
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

public class WialonProtocolDecoder extends BaseProtocolDecoder {

    private Long deviceId;
    private String deviceImei;
    private String tableName;
    

    public WialonProtocolDecoder(DataManager dataManager, String protocol, Properties properties) {
        super(dataManager, protocol, properties);
    }

    private static final Pattern pattern = Pattern.compile(
            "(\\d{2})(\\d{2})(\\d{2});" +  // Date (DDMMYY)
            "(\\d{2})(\\d{2})(\\d{2});" +  // Time (HHMMSS)
            "(\\d{2})(\\d{2}\\.\\d+);" +   // Latitude (DDMM.MMMM)
            "([NS]);" +
            "(\\d{3})(\\d{2}\\.\\d+);" +   // Longitude (DDDMM.MMMM)
            "([EW]);" +
            "(\\d+\\.?\\d*)?;" +           // Speed
            "(\\d+\\.?\\d*)?;" +           // Course
            "(?:(\\d+\\.?\\d*)|NA);" +     // Altitude
            "(?:(\\d+)|NA)" +              // Satellites
            ".*");                         // Full format

    private void sendResponse(Channel channel, String prefix, Integer number) {
        if (channel != null) {
            StringBuilder response = new StringBuilder(prefix);
            if (number != null) {
                response.append(number);
            }
            response.append("\r\n");
            channel.write(response.toString());
        }
    }
    
    private Position decodePosition(String substring) {
        
        // Parse message
        Matcher parser = pattern.matcher(substring);
        if (deviceId == null || !parser.matches()) {
            return null;
        }

        // Create new position
        Position position = new Position();
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        position.setDeviceId(deviceId);
        position.setTableName(tableName);
        position.setImei(deviceImei);

        Integer index = 1;

        // Date and Time
        Calendar time = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        time.clear();
        time.set(Calendar.DAY_OF_MONTH, Integer.valueOf(parser.group(index++)));
        time.set(Calendar.MONTH, Integer.valueOf(parser.group(index++)) - 1);
        time.set(Calendar.YEAR, 2000 + Integer.valueOf(parser.group(index++)));
        time.set(Calendar.HOUR_OF_DAY, Integer.valueOf(parser.group(index++)));
        time.set(Calendar.MINUTE, Integer.valueOf(parser.group(index++)));
        time.set(Calendar.SECOND, Integer.valueOf(parser.group(index++)));
        position.setTime(time.getTime());

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
        String speed = parser.group(index++);
        if (speed != null) {
            position.setSpeed(Double.valueOf(speed) * 0.539957);
        } else {
            position.setSpeed(0.0);
        }

        // Course
        String course = parser.group(index++);
        if (course != null) {
            position.setCourse(Double.valueOf(course));
        } else {
            position.setCourse(0.0);
        }

        // Altitude
        String altitude = parser.group(index++);
        if (altitude != null) {
            position.setAltitude(Double.valueOf(altitude));
        } else {
            position.setAltitude(0.0);
        }

        // Satellites
        String satellites = parser.group(index++);
        if (satellites != null) {
            position.setValid(Integer.valueOf(satellites) >= 3);
            extendedInfo.set("satellites", satellites);
        } else {
            position.setValid(false);
        }

        // Extended info
        position.setExtendedInfo(extendedInfo.getStyle(getDataManager().getStyleInfo()));

        return position;
    }

    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, Object msg)
            throws Exception {

        String sentence = (String) msg;

        // Detect device ID
        if (sentence.startsWith("#L#")) {
            String imei = sentence.substring(3, sentence.indexOf(';'));
            try {
                Device device = getDataManager().getDeviceByImei(imei);
                deviceImei = imei;
                deviceId = device.getId();
                tableName = device.getTableName();
                sendResponse(channel, "#AL#", 1);
            } catch(Exception error) {
                Log.warning("Unknown device - " + imei);
            }
        }

        // Heartbeat
        else if (sentence.startsWith("#P#")) {
            sendResponse(channel, "#AP#", null);
        }
        
        // Parse message
        else if (sentence.startsWith("#SD#") || sentence.startsWith("#D#")) {

            Position position = decodePosition(
                    sentence.substring(sentence.indexOf('#', 1) + 1));

            if (position != null) {
                sendResponse(channel, "#AD#", 1);
                return position;
            }
        }
        
        else if (sentence.startsWith("#B#")) {
            
            String[] messages = sentence.substring(sentence.indexOf('#', 1) + 1).split("\\|");
            List<Position> positions = new LinkedList<Position>();

            for (String message : messages) {
                Position position = decodePosition(message);
                if (position != null) {
                    positions.add(position);
                }
            }

            sendResponse(channel, "#AB#", messages.length);
            if (!positions.isEmpty()) {
                return positions;
            }
        }

        return null;
    }

}
