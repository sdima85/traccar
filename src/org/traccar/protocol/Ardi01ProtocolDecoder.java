/*
 * Copyright 2015 Anton Tananaev (anton.tananaev@gmail.com)
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

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.traccar.BaseProtocolDecoder;
import org.traccar.database.DataManager;
import org.traccar.helper.Log;
import org.traccar.model.ExtendedInfoFormatter;
import org.traccar.model.Position;

import java.util.Calendar;
import java.util.Properties;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Ardi01ProtocolDecoder extends BaseProtocolDecoder {

    public Ardi01ProtocolDecoder(DataManager dataManager, String protocol, Properties properties) {
        super(dataManager, protocol, properties);
    }

    private static final Pattern pattern = Pattern.compile(
            "(\\d+)," +                    // IMEI
            "(\\d{4})(\\d{2})(\\d{2})" +   // Date (YYYYMMDD)
            "(\\d{2})(\\d{2})(\\d{2})," +  // Time (HHMMSS)
            "(-?\\d+\\.\\d+)," +           // Longitude
            "(-?\\d+\\.\\d+)," +           // Latitude
            "(\\d+\\.?\\d*)," +            // Speed
            "(\\d+\\.?\\d*)," +            // Course
            "(-?\\d+\\.?\\d*)," +          // Altitude
            "(\\d+)," +                    // Satellites
            "(\\d+)," +                    // Event
            "(\\d+)," +                    // Battery
            "(-?\\d+)");                   // Temperature

    @Override
    protected Object decode(
            ChannelHandlerContext ctx, Channel channel, Object msg)
            throws Exception {

        String sentence = (String) msg;
        
        // Parse message
        Matcher parser = pattern.matcher(sentence);
        if (!parser.matches()) {
            return null;
        }

        // Create new position
        Position position = new Position();
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        Integer index = 1;

        // Detect device
        String imei = parser.group(index++);
        try {
            position.setDeviceId(getDataManager().getDeviceByImei(imei).getId());
        } catch(Exception error) {
            Log.warning("Unknown device - " + imei);
            return null;
        }
        
        // Date and time
        Calendar time = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        time.clear();
        time.set(Calendar.YEAR, Integer.valueOf(parser.group(index++)));
        time.set(Calendar.MONTH, Integer.valueOf(parser.group(index++)) - 1);
        time.set(Calendar.DAY_OF_MONTH, Integer.valueOf(parser.group(index++)));
        time.set(Calendar.HOUR_OF_DAY, Integer.valueOf(parser.group(index++)));
        time.set(Calendar.MINUTE, Integer.valueOf(parser.group(index++)));
        time.set(Calendar.SECOND, Integer.valueOf(parser.group(index++)));
        position.setTime(time.getTime());
        
        // Location data
        position.setLongitude(Double.valueOf(parser.group(index++)));
        position.setLatitude(Double.valueOf(parser.group(index++)));
        position.setSpeed(Double.valueOf(parser.group(index++)) * 0.539957);
        position.setCourse(Double.valueOf(parser.group(index++)));
        position.setAltitude(Double.valueOf(parser.group(index++)));
        
        // Satellites
        int satellites = Integer.valueOf(parser.group(index++));
        position.setValid(satellites >= 3);
        extendedInfo.set("satellites", satellites);
        
        // Event
        extendedInfo.set("event", parser.group(index++));

        // Input
        extendedInfo.set("battery", parser.group(index++));

        // Output
        extendedInfo.set("temperature", parser.group(index++));

        position.setExtendedInfo(extendedInfo.toString());
        return position;
    }

}
