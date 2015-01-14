/*
 * Copyright 2013 Anton Tananaev (anton.tananaev@gmail.com)
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

import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;

import org.traccar.BaseProtocolDecoder;
import org.traccar.database.DataManager;
import org.traccar.helper.ChannelBufferTools;
import org.traccar.helper.Crc;
import org.traccar.helper.Log;
import org.traccar.model.Device;
import org.traccar.model.DeviceCommand;
import org.traccar.model.ExtendedInfoFormatter;
import org.traccar.model.Position;

public class TeltonikaProtocolDecoder extends BaseProtocolDecoder {
    
    private long deviceId;
    private String deviceImei;
    private String tableName;

    public TeltonikaProtocolDecoder(DataManager dataManager, String protocol, Properties properties) {
        super(dataManager, protocol, properties);
    }

    private void parseIdentification(Channel channel, ChannelBuffer buf) {
        boolean result = false;

        int length = buf.readUnsignedShort();
        String imei = buf.toString(buf.readerIndex(), length, Charset.defaultCharset());
        try {
            Device device = getDataManager().getDeviceByImei(imei);
            deviceImei = imei;
            deviceId = device.getId();
            tableName = device.getTableName();
            
            result = true;
        } catch(Exception error) {
            Log.warning("Unknown device - " + imei);
        }
        
        if (channel != null) {
            ChannelBuffer response = ChannelBuffers.directBuffer(1);
            response.writeByte(result ? 1 : 0);
            channel.write(response);
            
            sendCommand(channel);
        }
    }

    private static boolean checkBit(long mask, int bit) {
        long checkMask = 1 << bit;
        return (mask & checkMask) == checkMask;
    }

    private static final int CODEC_GH3000 = 0x07;
    private static final int CODEC_FM4X00 = 0x08;
    private static final int CODEC_12 = 0x0C;
    
    private void sendCommand(Channel channel){       
        List<DeviceCommand> commands = getDataManager().getCommandsByImei(deviceImei);
        
        if(commands==null){return;}
        
        //
        int count=7; //        
        //Не больше 4х в пакете
        int respCmd = (commands.size() > 3 ? 4 : commands.size());
        //int lenCmd = 0;
        String cmd = "";
        
        for (int i = 0; i < respCmd; i++) {
            DeviceCommand command = commands.get(i);
            cmd += command.getCommand();
        }
        
        int cmdLen = (cmd.length()/2);
        ChannelBuffer response2 = ChannelBuffers.directBuffer(count + cmdLen);
        //1 - 2 байта = 0x0000
        response2.writeShort(0x0000);
        //2 - Длина данных 2 байта без CRC16 = 0x04
        response2.writeShort(cmdLen + 1);
        //Данные
        //3 - Кол-во пакетов 1 байт = 0x01
        response2.writeByte(respCmd);
        //4
        response2.writeBytes(ChannelBufferTools.convertHexString(cmd));
        //6 - CRC16 2 байта (с №3 ПО №5 включительно) =             
        response2.writeShort(Crc.crc16_A001(response2.toByteBuffer(4, cmdLen+1)));
        channel.write(response2);
        Log.debug("Response="+ChannelBufferTools.readHexString(response2,(count + cmdLen)*2));
        
        for (int i = 0; i < respCmd; i++) {
            //update to send
            try{
                getDataManager().addCommand(commands.get(0));
            }catch(SQLException e){
                Log.warning(e);
            }
            //delete
            commands.remove(0);
        }
        
        
        /*
        int index=0;
        for (int i=0; i<respCmd;i++) {
            DeviceCommand command=commands.get(i);
            
        //for (DeviceCommand command : commands) {
            index++; 
            
            int cmdLen = (command.getCommand().length()/2);
            
            ChannelBuffer response2 = ChannelBuffers.directBuffer(count + cmdLen);
            //1 - 2 байта = 0x0000
            response2.writeShort(0x0000);
            //2 - Длина данных 2 байта без CRC16 = 0x04
            response2.writeShort(cmdLen+1);
            //Данные
            //3 - Кол-во пакетов 1 байт = 0x01
            response2.writeByte(0x01);
            //4
            response2.writeBytes(ChannelBufferTools.convertHexString(command.getCommand()));
            //6 - CRC16 2 байта (с №3 ПО №5 включительно) =             
            response2.writeShort(Crc.crc16_A001(response2.toByteBuffer(4, 4)));
            channel.write(response2);
            Log.debug("Response="+ChannelBufferTools.readHexString(response2,(count + cmdLen)*2));
            
            //ChannelBufferTools.convertHexString();
            //ChannelBuffers.wrappedBuffer()
            //commands.remove(command);
            
            if(index>=4){
                break;
            }
        }
        */
        /*
        ChannelBuffer response2 = ChannelBuffers.directBuffer(count + (2 * 3));

        //1 - 2 байта = 0x0000
        response2.writeShort(0x0000);
        //2 - Длина данных 2 байта без CRC16 = 0x04
        //response2.writeShort(0x0004);
        response2.writeShort(0x0007);
        //Данные
        //3 - Кол-во пакетов 1 байт = 0x01
        //response2.writeByte(0x01);
        response2.writeByte(0x02);


        //32 Конфигурационный пакет от сервера – запрос значения параметра
        //4 - ID пакета 1 байт = 0x20
        response2.writeByte(0x20);
        //5 - Параметр 2 байта (имя сервера) = 0x00F5
        response2.writeShort(0x00F5);

        //Посылка №2
        //4 - ID пакета 1 байт = 0x20
        response2.writeByte(0x20);
        //5 - Параметр 2 байта (порт сервера) = 0x00F5
        response2.writeShort(0x00F6);


        //6 - CRC16 2 байта (с №3 ПО №5 включительно) =             
        response2.writeShort(Crc.crc16_A001(response2.toByteBuffer(4, 7)));
        //0000 0004 01 20 00f5 71c0 +++
        //0000 0015 01 21 00f5 10 62692e7175616e742e6e65742e756100 ad11

        channel.write(response2);
        Log.debug("Response="+ChannelBufferTools.readHexString(response2,13*2));
        */
    }
    
    private List<Position> parseLocation(Channel channel, ChannelBuffer buf) {
        List<Position> positions = new LinkedList<Position>();
        
        buf.skipBytes(4); // marker
        buf.readUnsignedInt(); // data length
        int codec = buf.readUnsignedByte(); // codec
        
        if (codec == CODEC_12) {
            // TODO: decode serial port data
            return null;
        }
        
        int count = buf.readUnsignedByte();
        
        for (int i = 0; i < count; i++) {
            Position position = new Position();
            ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
            
            position.setDeviceId(deviceId);
            position.setTableName(tableName);
            position.setImei(deviceImei);
            
            int globalMask = 0x0f;
            
            if (codec == CODEC_GH3000) {

                long time = buf.readUnsignedInt() & 0x3fffffff;
                time += 1167609600; // 2007-01-01 00:00:00
                position.setTime(new Date(time * 1000));
                
                globalMask = buf.readUnsignedByte();
                if (!checkBit(globalMask, 0)) {
                    return null;
                }
                
                int locationMask = buf.readUnsignedByte();
                
                if (checkBit(locationMask, 0)) {
                    position.setLatitude(Double.valueOf(buf.readFloat()));
                    position.setLongitude(Double.valueOf(buf.readFloat()));
                }
                
                if (checkBit(locationMask, 1)) {
                    position.setAltitude((double) buf.readUnsignedShort());
                }
                
                if (checkBit(locationMask, 2)) {
                    position.setCourse(buf.readUnsignedByte() * 360.0 / 256);
                }
                
                if (checkBit(locationMask, 3)) {
                    position.setSpeed(buf.readUnsignedByte() * 0.539957);
                }
                
                if (checkBit(locationMask, 4)) {
                    int satellites = buf.readUnsignedByte();
                    extendedInfo.set("satellites", satellites);
                    position.setValid(satellites >= 3);
                }
                
                if (checkBit(locationMask, 5)) {
                    extendedInfo.set("area", buf.readUnsignedShort());
                    extendedInfo.set("cell", buf.readUnsignedShort());
                }
                
                if (checkBit(locationMask, 6)) {
                    extendedInfo.set("gsm", buf.readUnsignedByte());
                }
                
                if (checkBit(locationMask, 7)) {
                    extendedInfo.set("operator", buf.readUnsignedInt());
                }

            } else {

                position.setTime(new Date(buf.readLong()));

                extendedInfo.set("priority", buf.readUnsignedByte());

                position.setLongitude(buf.readInt() / 10000000.0);
                position.setLatitude(buf.readInt() / 10000000.0);
                position.setAltitude((double) buf.readShort());
                position.setCourse((double) buf.readUnsignedShort());

                int satellites = buf.readUnsignedByte();
                extendedInfo.set("satellites", satellites);

                position.setValid(satellites != 0);

                position.setSpeed(buf.readUnsignedShort() * 0.539957);

                extendedInfo.set("event", buf.readUnsignedByte());

                buf.readUnsignedByte(); // total IO data records

            }
            
            // Read 1 byte data
            if (checkBit(globalMask, 1)) {
                int cnt = buf.readUnsignedByte();
                for (int j = 0; j < cnt; j++) {
                    extendedInfo.set("io" + buf.readUnsignedByte(), buf.readUnsignedByte());
                }
            }

            
            // Read 2 byte data
            if (checkBit(globalMask, 2)) {
                int cnt = buf.readUnsignedByte();
                for (int j = 0; j < cnt; j++) {
                    extendedInfo.set("io" + buf.readUnsignedByte(), buf.readUnsignedShort());
                }
            }

            // Read 4 byte data
            if (checkBit(globalMask, 3)) {
                int cnt = buf.readUnsignedByte();
                for (int j = 0; j < cnt; j++) {
                    extendedInfo.set("io" + buf.readUnsignedByte(), buf.readUnsignedInt());
                }
            }

            // Read 8 byte data
            if (codec == CODEC_FM4X00) {
                int cnt = buf.readUnsignedByte();
                for (int j = 0; j < cnt; j++) {
                    extendedInfo.set("io" + buf.readUnsignedByte(), buf.readLong());
                }
            }
        
            position.setExtendedInfo(extendedInfo.getStyle(getDataManager().getStyleInfo()));
            positions.add(position);
        }
        
        if (channel != null) {
            ChannelBuffer response = ChannelBuffers.directBuffer(4);
            response.writeInt(count);
            channel.write(response);
            sendCommand(channel);
        }
        
        return positions;
    }
    
    private List<DeviceCommand> parseCommand(Channel channel, ChannelBuffer buf){
        //0000 0015 01 21 00f5 10 62692e7175616e742e6e65742e756100 ad11
        buf.skipBytes(2); //
        buf.readUnsignedShort(); // data length
        int count = buf.readUnsignedByte(); // count
        
        List<DeviceCommand> commands = new LinkedList<DeviceCommand>();        

        for(int i = 0; i < count; i++){
            ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
            DeviceCommand command = new DeviceCommand();
            command.setDeviceId(deviceId);
            command.setImei(deviceImei);
            
            //int idx = buf.readerIndex();
            int codec = buf.readUnsignedByte(); // codec
            extendedInfo.set("codec", codec);
            
            int commandStart = 1;
            int commandLength = 0;
            
            //Ответ серверу на запрос значения параметра
            if(codec == 33){//Ответ треккера на конфигурационный пакет – запрос значения параметра
                int paramId = buf.readUnsignedShort();
                int length = buf.readUnsignedByte();                    
                String paramVal=getReadFromIdConfig(buf, paramId, length);
                               
                Log.debug("codec="+codec+" paramId="+paramId+" paramVal="+paramVal);
                extendedInfo.set("param", paramId);
                extendedInfo.set("value", paramVal);
                commandStart = length + 4;
                commandLength = length + 4;
            }
            else if(codec == 37){//Ответ треккера на конфигурационный пакет – установка значения параметра
                int paramId = buf.readUnsignedShort();
                int isSet = buf.readUnsignedByte(); //0 – неверный параметр, 255 – параметр установлен

                extendedInfo.set("param", paramId);
                extendedInfo.set("isset", isSet);                
                commandStart = 4;
                commandLength = 4;
            }
            //else if(codec == 40){//Пакет с командой от сервера
            //    int cmd = buf.readUnsignedByte(); //Код команды
            //}
            else if(codec == 41){//Ответ на пакет с командой от сервера
                short cmd = buf.readUnsignedByte();
                short length = buf.readUnsignedByte();   
            }
            else if(codec == 42){//Ответ на неподдерживаемую команду от сервера
                short cmd = buf.readUnsignedByte();
            }
            int idx = buf.readerIndex();
            buf.readerIndex(idx-commandStart);
            //buf.skipBytes(-commandStart);
            String hex = ChannelBufferTools.readHexString(buf, commandLength*2); 
            command.setCommand(hex);
            
            command.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
            commands.add(command);
        }
        
        
        return commands;
    }
    
    @Override
    protected Object decode(ChannelHandlerContext ctx, Channel channel, Object msg)
            throws Exception {
        
        ChannelBuffer buf = (ChannelBuffer) msg;
        
        if ((buf.getUnsignedShort(0)==0)&&(buf.getUnsignedShort(2)>0)){
            Log.debug("config");
            return parseCommand(channel, buf);
            
        } else if (buf.getUnsignedShort(0) > 0) {
            Log.debug("parseIdentification");
            parseIdentification(channel, buf);
        }
        else {
            Log.debug("parseLocation");
            return parseLocation(channel, buf);
        }
        
        return null;
    }

    private String getReadFromIdConfig(ChannelBuffer buf, int paramId, int length){
        String paramVal="";
        switch (paramId) {
            case 242://Точка доступа GPRS ( по умолчанию 3g.utel.ua )
            case 243://Логин доступа GPRS ( по умолчанию не установлен. )
            case 244://Пароль доступа GPRS ( по умолчанию не установлен. )
            case 245://server
            case 252://Логин доступа по СМС
            case 253://Пароль доступа по СМС
            case 261://Авторизированный телефонный номер
            case 262://
            case 263://
            case 264://
            case 265://
            case 266://
            case 267://
            case 268://
            case 269://Авторизированный телефонный номер

            paramVal = buf.toString(buf.readerIndex(), length-1, Charset.defaultCharset());
            buf.skipBytes(length);
            break;

            case 11://Период съёма по времени при выключенном зажигании ( по умолчанию 30 сек)
            case 12://Период съёма по расстоянию ( по умолчанию 500 м)
            case 13://Период съёма по азимуту ( по умолчанию 10° )
            case 232://Кол-во записей в пакете ( по умолчанию 0 )
            case 246://port
            case 284://Таймаут начала движения по акселерометру ( по умолчанию 20*0,1=2сек.)
            case 285://Таймаут остановка движения по акселерометру ( по умолчанию 50 *0,1=5сек.)
            case 270://Период передачи данных на сервер ( по умолчанию 60 сек)
            case 903://Период съёма по времени при включенном зажигании ( по умолчанию 30 сек)
            case 905://Период ожидания между попытками в серии ( по умолчанию 60 сек)
            paramVal = ""+buf.readUnsignedShort();
            break;

            case 281://Угол отклонения акселерометра по оси X ( по умолчанию 3°)
            case 282://Y
            case 283://Z
            case 900://Разрешение съёма по времени ( по умолчанию 1)
            case 901://Разрешение съёма по расстоянию    
            case 902://Разрешение съёма по азимуту ( по умолчанию 1)
            case 904://Кол-во попыток в серии соединения с сервером ( по умолчанию 3)

            paramVal = ""+buf.readUnsignedByte();
            break;
        }
        return paramVal;
    }
}
