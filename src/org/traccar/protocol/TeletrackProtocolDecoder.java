/*
 */
package org.traccar.protocol;

import java.math.BigInteger;
import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.TimeZone;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;

import org.traccar.BaseProtocolDecoder;
import org.traccar.database.DataManager;
import org.traccar.helper.Log;
import org.traccar.model.Device;
import org.traccar.model.DeviceCommand;
import org.traccar.model.ExtendedInfoFormatter;
import org.traccar.model.Position;

public class TeletrackProtocolDecoder extends BaseProtocolDecoder {
    
    private long deviceId;
    private String deviceImei;
    private String tableName;
    
    private String packetId;
    private int packetLen;
    private int packetIndex;    
    private ChannelBuffer packetBuf;
    
    private int priorSendCmdId;    
    private int priorSendRowId;
    

    public TeletrackProtocolDecoder(DataManager dataManager, String protocol, Properties properties) {
        super(dataManager, protocol, properties);
        packetClear();
        
        priorSendCmdId=0;
        priorSendRowId=0;
    }
    
    private final int BIN_LENGTH = 0x20; //32
    private final int ATR_LENGTH = 4;
    
    private final int A1_ATR_LENGTH = 3;
    private final int A1_EMAIL_SIZE = 13;
    
    private ChannelBuffer getAck(boolean error){
        ChannelBuffer response = ChannelBuffers.directBuffer(1);

        short r = 0x30; //Признак пакета
        //Результат обработки запроса.
        //0b0 – Все ок.
        //0b1 – Ошибка или отрицательный ответ на запрос.
        r |= (error ? 8 : 0);    
        
        List<DeviceCommand> commands = getDataManager().getCommandsByImei(deviceImei);
        if(commands != null){
            //Флаг наличия команд в очереди на сервере.
            //0b0 – Ничего нет.
            //0b1 – Очередь не пуста.
            r |= (commands.size() > 0 ? 4 : 0);
        }        
        response.writeByte(r);        
        return response;
    }

    private void parseIdentification(Channel channel, ChannelBuffer buf, boolean ext) {
        boolean result = false;
        
        buf.skipBytes(ATR_LENGTH);
        
        if(ext){
            //версия протокола
            int protocolLen = 0;
            for (int i=buf.readerIndex(); i < buf.writerIndex(); i++){
                if(buf.getByte(i) == 0){
                    protocolLen = i - buf.readerIndex();
                    break;
                }
            }
            String protocol = buf.toString(buf.readerIndex(), protocolLen, Charset.defaultCharset());
            buf.skipBytes(protocolLen+1);
        }
        //Логин
        int loginLen = 0;
        for (int i=buf.readerIndex(); i < buf.writerIndex(); i++){
            if(buf.getByte(i) == 0){
                loginLen = i - buf.readerIndex();
                break;
            }
        }
        
        String devID = buf.toString(buf.readerIndex(), loginLen, Charset.defaultCharset());
        buf.skipBytes(loginLen+1);
        
        //Проверка пароля ???
        int pwdLen = 0;
        for (int i=buf.readerIndex(); i < buf.writerIndex(); i++){
            if(buf.getByte(i) == 0){
                pwdLen = i - buf.readerIndex();
                break;
            }
        }        
        String DevPwd = buf.toString(buf.readerIndex(), pwdLen ,Charset.defaultCharset());
        
        try {
            Device device = getDataManager().getDeviceByImei(devID);
            deviceImei = devID;
            deviceId = device.getId();
            tableName = device.getTableName();
            
            result = true;
        } catch(Exception error) {
            Log.warning("Unknown device - " + devID);
        }
        
        if (channel != null) {
            channel.write(getAck(false));            
            //sendCommand(channel);
        }
    }
    
    private long readUInt(ChannelBuffer buf){
        int b0 = buf.readUnsignedByte();
        int b1 = buf.readUnsignedByte();
        int b2 = buf.readUnsignedByte();
        int b3 = buf.readUnsignedByte();
        
        long res = b0 & 0xFF;
        res |= ((b1 << 8) & 0xFF00);
        res |= ((b2 << 16) & 0xFF0000);
        res |= ((b3 << 24) & 0xFF000000);
    
        return res;
    }

    /*
    * Отправка команд на устройства
    */
    private void sendCommand(Channel channel){       
        List<DeviceCommand> commands = getDataManager().getCommandsByImei(deviceImei);
        
        if(commands == null){ return; }      
        if(commands.isEmpty()){ return; }
        
        String styleInfo = this.getDataManager().getStyleInfo();
        DeviceCommand command = commands.get(0);
        //EncodeConfigQuery
        String data = command.getData();
        String cmdName = "";
        String paramId = "0";
        byte[] sendData = null;
        
        if(styleInfo.equals("quant")){
            cmdName = this.getDataManager().getQuantParametr(data,"command");
            paramId = this.getDataManager().getQuantParametr(data,"param");
            //String paramValue = this.getDataManager().getQuantParametr(data,"value");
        }        
        int cmdId = 0;
        if(paramId != null){
            cmdId = Integer.parseInt(paramId);
            priorSendCmdId=cmdId;
        }
        int rowId = (int) Integer.valueOf(command.getId().toString());
        priorSendRowId=rowId;
        
        
        if("reset".equals(cmdName)){
            sendData = EncodeDeviceReset(command);
            priorSendCmdId =10;
        }
        else if("getparam".equals(cmdName) && (cmdId == 0x24)){ //DataGpsQuery - 36
            sendData = EncodeDataGpsQuery(command, cmdId);            
        }
        else if("getparam".equals(cmdName)){            
            sendData = EncodeConfigQuery(command, cmdId);
        }
        else if("setparam".equals(cmdName)){
            switch(cmdId){
//            MessageToDriver Сообщение водителю = 10       
                case 10:
                    sendData = EncodeMessageToDriver(command, cmdId);
                    break;
//            SMSConfigSet Установка параметров для адресов SMS, E-MAIL и WEB SERVER (GPRS) сообщений = 11                  
                case 11:
                    sendData = EncodeSmsAddrConfigSet(command, cmdId);
                    break;
//            PhoneConfigSet Установка конфигурации телефонных номеров = 12.  
                case 12:
                    sendData = EncodePhoneNumberConfigSet(command, cmdId);
                    break;
//            EventConfigSet Установка конфигурации событий = 13.  
                case 13:
                    sendData = EncodeEventConfigSet(command, cmdId);
                    break;    
//            UniqueConfigSet Установка конфигурации уникальных параметров = 14.  
                case 14:
                    sendData = EncodeUniqueConfigSet(command, cmdId);
                    break;       
//            ZoneConfigSet Установка конфигурации контрольных зон = 15.  
                case 15:
                    sendData = EncodeZoneConfigSet(command, cmdId);
                    break; 
//            IdConfigSet Установка конфигурации идентификационных параметров = 17.  
                case 17:
                    sendData = EncodeIdConfigSet(command, cmdId);
                    break; 
//
//            DataGpsAuto Автоматическая отправка GPS данных = 49.  
                //case 49:
                //    sendData = (command, cmdId);
                //    break; 
//            GprsBaseConfigSet Установка основных настроек GPRS = 50.  
                case 50:
                    sendData = EncodeGprsBaseConfigSet(command, cmdId);
                    break; 
//            GprsProviderConfigSet Установка настроек подключения к провайдеру GPRS  =54.  
                case 54:
                    sendData = EncodeGprsProviderConfigSet(command, cmdId);
                    break; 
//            GprsEmailConfigSet Установка настроек Email GPRS = 51.  
                case 51:
                    sendData = EncodeGprsEmailConfigSet(command, cmdId);
                    break; 
//            GprsSocketConfigSet Установка настроек Socket GPRS = 52.  
                case 52:
                    sendData = EncodeGprsSocketConfigSet(command, cmdId);
                    break; 
//            GprsFtpConfigSet Установка настроек FTP GPRS = 53.                  
                case 53:
                    sendData = EncodeGprsFtpConfigSet(command, cmdId);
                    break; 
                default:                    
                    sendData = new byte[1];
                    sendData[0] = 0;
            }
        }
        
        if(sendData != null){
            String msg;
            if(sendData.length > 1){
                msg = TeletrackProtocolA1.GetStringFromByteArray(sendData);
                command.setCommand(msg);
                channel.write(ChannelBuffers.wrappedBuffer(sendData));
            }
            else {
                command.setCommand("unknown");
            }
            
            try{
                getDataManager().addCommand(commands.get(0));
            }catch(SQLException e){
                Log.warning(e);
            }
            commands.remove(0);
        }        
    }
    
    private Position parseLocation(ChannelBuffer buf){
        Position position = new Position();
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());

        position.setDeviceId(deviceId);
        position.setTableName(tableName);
        position.setImei(deviceImei);

        long logId = readUInt(buf); //4b
        extendedInfo.set("logid", logId);

        long time = readUInt(buf); //4b
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        java.util.Date date= new java.util.Date((time*1000));
        position.setTime(date);

        long lat = readUInt(buf); //buf.readInt(); //4b // / 600000.0;
        position.setLatitude(lat / 60000.0); //* 50 / 3 / 10000000.0);

        long lon = readUInt(buf); //buf.readInt(); //4b // / 600000.0;
        position.setLongitude(lon / 60000.0); //* 50 / 3 / 10000000.0);

        double speed = buf.readUnsignedByte()* 1.85; //1b
        position.setSpeed(speed);

        double direct = (buf.readUnsignedByte() * 360) / 255; //1b
        position.setCourse(direct);

        double sp = buf.readUnsignedByte(); //1b //ускорение
        extendedInfo.set("acceleration", sp);

        short flags = buf.readUnsignedByte(); //1b
        position.setValid((flags & 0x08) != 0);

        String sensors = "";  //8b
        sensors += String.format("%8s", Integer.toBinaryString(buf.readUnsignedByte() & 0xFF)).replace(' ', '0'); //1
        sensors += String.format("%8s", Integer.toBinaryString(buf.readUnsignedByte() & 0xFF)).replace(' ', '0'); //
        sensors += String.format("%8s", Integer.toBinaryString(buf.readUnsignedByte() & 0xFF)).replace(' ', '0'); //
        sensors += String.format("%8s", Integer.toBinaryString(buf.readUnsignedByte() & 0xFF)).replace(' ', '0'); //
        sensors += String.format("%8s", Integer.toBinaryString(buf.readUnsignedByte() & 0xFF)).replace(' ', '0'); //
        sensors += String.format("%8s", Integer.toBinaryString(buf.readUnsignedByte() & 0xFF)).replace(' ', '0'); //
        sensors += String.format("%8s", Integer.toBinaryString(buf.readUnsignedByte() & 0xFF)).replace(' ', '0'); //
        sensors += String.format("%8s", Integer.toBinaryString(buf.readUnsignedByte() & 0xFF)).replace(' ', '0'); //8
        Long sens = new BigInteger(sensors, 2).longValue();//Long.parseLong(sensors, 2);                
        extendedInfo.set("sensors", Long.toUnsignedString(sens));
        
        long events = buf.readUnsignedByte(); //3b
        events += (buf.readUnsignedByte() << 8);
        events += (buf.readUnsignedByte() << 16);
        extendedInfo.set("events", events);

        short crc = buf.readUnsignedByte(); //1b
        
        position.setExtendedInfo(extendedInfo.getStyle(getDataManager().getStyleInfo()));    
        return position; 
    }
    
    private List<Position> parseMultiBinLocation(Channel channel, ChannelBuffer buf) {
        List<Position> positions = new LinkedList<Position>();
        
        buf.skipBytes(ATR_LENGTH); // marker
        short count = buf.readUnsignedByte();
        
        for (int i = 0; i < count; i++) {
            Position position = parseLocation(buf);
            positions.add(position);
        }
        
        if (channel != null) {
            ChannelBuffer response = getAck(false);
            channel.write(response);
        }
        return positions;
    }
    
    private int calcLenPackedMultiBin(ChannelBuffer buf){
        //К сожалению, в этом пакете не предусмотрено дополнительное поле 
        //с информацией о размере пакета PackedMultiBin, поэтому определить 
        //байт, в котором находится контрольная сумма, удастся только 
        //после «прохода» по всем упакованным данным.
        
        int len = ATR_LENGTH;
        short count = (short) (buf.getUnsignedByte(len) - 1);
        len += 1;
        
        // Первый целый пакет
        len += BIN_LENGTH;
        
        //
        for (int i = 0; i < count; i++) {
            
            if((len+5) > buf.readableBytes()){
                //len = 0;
                //break;
                return 0;
            }
            
            long mask = buf.getUnsignedInt(len);  
            len += 4;
            
            long val =  2147483648L;
            for (int j = 0; j < BIN_LENGTH; j++){
                long check = mask & val;
                if(check == val){
                    len += 1;
                    //short nval = buf.readUnsignedByte();
                    //bufStart.setByte(j, nval);
                }

                val = val >> 1;
            }
        }
        
        len += 1; //CRC
        if(len > buf.readableBytes()){
            return 0;
        }
        return len;
    }
    
    private List<Position> parsePackedMultiBinLocation(Channel channel, ChannelBuffer buf){
        List<Position> positions = new LinkedList<Position>();        
        buf.skipBytes(ATR_LENGTH); // marker
        
        short count = buf.readUnsignedByte();
        if(count > 0){
            ChannelBuffer bufStart = ChannelBuffers.directBuffer(BIN_LENGTH);
            bufStart.writeBytes(buf, buf.readerIndex(), BIN_LENGTH);
            buf.skipBytes(BIN_LENGTH);
            
            Position positionStart = parseLocation(bufStart);
            positions.add(positionStart);
            bufStart.readerIndex(0);
            count -= 1;            
            //Количество инкапсулированных пакетов Bin (от 0 до 255). 
            //Примечание: для получения информации о состоянии очереди команд 
            //можно использовать пустой пакет PackedMultiBin.
            for (int i = 0; i < count; i++) {
                long mask = buf.readUnsignedInt();  
                long val =  2147483648L;
                for (int j = 0; j < BIN_LENGTH; j++){
                    long check = mask & val;
                    if(check == val){
                        short nval = buf.readUnsignedByte();                        
                        bufStart.setByte(j, nval);
                    }                    
                    val = val >> 1;
                }                
                Position position = parseLocation(bufStart);
                positions.add(position);
                bufStart.readerIndex(0);
            }            
        }
        return positions;
    }
    
       
    private Object parseA1(Channel channel, ChannelBuffer buf){
        if(!"%%".equals(buf.toString(A1_EMAIL_SIZE+1, 2, Charset.defaultCharset()))){
            // Признак начала пакета расположен не на своем месте в позиции {0}, ожидалось в позиции 14
            return null;
        }
        byte[] destinationArray = new byte[0x90]; //144        
        buf.getBytes(0x10, destinationArray, 0, 0x90);
        
        int num2 = TeletrackProtocolA1.L1SymbolToValue(destinationArray[2]) << 2;
        if (num2 != 0x88){
            return null;
        }
        //
        byte[] buffer2 = new byte[2];
        System.arraycopy(destinationArray, 0, buffer2, 0, buffer2.length );

        short num3 = TeletrackProtocolA1.L1SymbolToValue(destinationArray[7]);
        //Util
        byte num4 = TeletrackProtocolA1.CalculateLevel1CRC(destinationArray, 0, num2);
        if(num3 != num4){
            //"A1_4. CRC не совпадает. Значение указанное в пакете {0}, расчитанное - {1}.", num3, num4));
            return null;
        }
        byte[] buffer3 = new byte[4];
        System.arraycopy(destinationArray, 3, buffer3, 0, 4);
        byte[] buffer4 = new byte[0x88];
        System.arraycopy(destinationArray, 8, buffer4, 0, 0x88);
        
        Object description = DecodeLevel3Message(TeletrackProtocolA1.L1Decode6BitTo8(buffer4));
        String ShortID = TeletrackProtocolA1.L1BytesToString(buffer3);
        
        if (description instanceof DeviceCommand){
            byte[] Source = new byte[160]; //144        
            buf.getBytes(0, Source, 0, 160);
            ((DeviceCommand)description).setCommand(TeletrackProtocolA1.GetStringFromByteArray(Source));
        }
        
        if(deviceId == 0){
            return null;
        }
        if (channel != null) {
            ChannelBuffer response = getAck(false);
            channel.write(response);
        }
        return description;
    }
    
    private void packetClear(){
        packetId = "";
        packetLen = 0;
        packetIndex = 0;
        packetBuf = null;
    }
    
    
    @Override
    protected Object decode(ChannelHandlerContext ctx, Channel channel, Object msg)
            throws Exception {
        ChannelBuffer buf = (ChannelBuffer) msg;        
        String PacketID = buf.toString(0, 4, Charset.defaultCharset());
        
        //A1
        if(buf.readableBytes() == 160){
            String str = buf.toString(A1_EMAIL_SIZE+1, 2, Charset.defaultCharset());
            if("%%".equals(str)){
                PacketID = "A1Packet";
            }
        }
        
        if("%%AU".equals(PacketID)){
            parseIdentification(channel, buf, false);
            packetClear();
        }
        else if("%%AE".equals(PacketID)){
            parseIdentification(channel, buf, true);
            packetClear();
        }
        else if("%%CR".equals(PacketID)){
            //CmdRequest – запрос серверу на отсылку команды A1; отправляется только клиентом.
            sendCommand(channel);
        }
        else if("%%MB".equals(PacketID)){
            packetClear();            
            short count = buf.getUnsignedByte(ATR_LENGTH);
            
            if(((count*BIN_LENGTH)+ATR_LENGTH+1)==buf.readableBytes()){
                //Пакет полный     
                List<Position> positions = parseMultiBinLocation(channel, buf);
                packetClear();
                return positions;
            }
            else {
                packetIndex = buf.readableBytes();
                packetId = PacketID;
                packetLen = (count*BIN_LENGTH)+ATR_LENGTH+1;
                packetBuf = ChannelBuffers.directBuffer(packetLen);
                packetBuf.writeBytes(buf);
            }
        }        
        else if("%%PB".equals(PacketID)){
            packetClear();
            //проверка длинны пакета
            int len = calcLenPackedMultiBin(buf);
            if(len==buf.readableBytes()){
                //Пакет полный     
                parsePackedMultiBinLocation(channel, buf);
                packetClear();
            }
            else {
                packetIndex = 0; //buf.readableBytes();
                packetId = PacketID;
                packetLen = len;
                packetBuf = ChannelBuffers.directBuffer(4096);
                packetBuf.writeBytes(buf);
            }
        }
        else if("%%RE".equals(PacketID)){
            //RESP_TT = Encoding.ASCII.GetBytes("%%RE");
            //Формат пакета Response - команда принята на устройстве
            buf.skipBytes(ATR_LENGTH); //Признак пакета 4 байта
            short res = buf.readUnsignedByte();
            //Код ответа 1 байт
            //0x00 – команда принята. / 0x01 – ошибка обработки команды.
            ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
            DeviceCommand config = new DeviceCommand();
            config.setDeviceId(deviceId);
            config.setImei(deviceImei);
            config.setCommand("%%RE"+res);
            extendedInfo.set("command", "getparam");
            extendedInfo.set("param", priorSendCmdId);
            extendedInfo.set("commandrow", priorSendRowId);
            extendedInfo.set("isset", (res > 0 ? 0 : 1));

            config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));            
            if (channel != null) {
                channel.write(getAck(false));
            }
            return config;
        }
        else if("A1Packet".equals(PacketID)){ //A1            
            return parseA1(channel, buf);
        }
        else if(("%%MB".equals(packetId)) && ((packetIndex + buf.readableBytes())<=packetLen)){
            packetIndex += buf.readableBytes();
            packetBuf.writeBytes(buf);
        }
        else if(buf.readableBytes() == 32){ //BIN
            Position position = parseLocation(buf);            
            if (channel != null) {
                ChannelBuffer response = getAck(false);
                channel.write(response);
            }
            return position;
        }      
        
        // Обработка склеенных данных
        if(("%%MB".equals(packetId)) && (packetIndex == packetLen)){
            List<Position> positions = parseMultiBinLocation(channel, packetBuf);
            packetClear();
            return positions;
        }
        else if(("%%PB".equals(packetId))){
            if(packetIndex == 0){
                packetIndex = 1;
            }
            else {
                packetBuf.writeBytes(buf);
                int len = calcLenPackedMultiBin(buf);                
                if(len > 0){
                    List<Position> positions = parsePackedMultiBinLocation(channel, packetBuf);
                    packetClear();
                    return positions;
                }
            }
        }
        return null;
    }
   
    /*
    * Обработка сообщения на 3-ем уровне LEVEL3 
    * Parameters command byte[102] Пакет команды LEVEL3
    */
    private Object DecodeLevel3Message(byte[] command){
        Object result;
        if (command.length != 0x66){
            return null;
            //throw new A1Exception(string.Format(
            //"A1_1. Длина пакета на уровне {0} не соответствует требованиям протокола. Ожидалось {1} байт, принято - {2}.", "LEVEL3", (byte) 0x66, command.Length));
        }
        byte commandID = command[0];
        int messageID = TeletrackProtocolA1.L4ToInt16(command, 1); //BytesToUShort(command, 1);
        byte[] destinationArray = new byte[0x63];
        System.arraycopy(command, 3, destinationArray, 0, 0x63);        
        
        switch (commandID){
            case 0x15: //21
            case 0x29: //41
                result = GetSmsAddrConfig(destinationArray, commandID, messageID);
                break;

            case 0x16: //22
            case 0x2a: //42
                result = GetPhoneNumberConfig(destinationArray, commandID, messageID);
                break;
            case 0x0D: //13
            case 0x17: //23
            case 0x2b: //43
                result = GetEventConfig(destinationArray, commandID, messageID);
                break;

            case 0x18: //24
            case 0x2c: //44
                result = GetUniqueConfig(destinationArray, commandID, messageID);
                break;

            case 0x19: //25
                result = GetZoneConfigConfirm(destinationArray, commandID, messageID);
                break;

            case 0x1b: //27
            case 0x2f: //47
                result = GetIdConfig(destinationArray, commandID, messageID);                
                break;

            case 0x2e: //46
            case 0x31: //49
                result = GetDataGps(destinationArray, commandID, messageID);
                break;

            case 60:
            case 80:
                result = GetGprsBaseConfig(destinationArray, commandID, messageID);
                break;

            case 0x3d: //61
            case 0x51: //81
                result = GetGprsEmailConfig(destinationArray, commandID, messageID);
                break;

            case 0x3e: //62
            case 0x52: //82
                result = GetGprsSocketConfig(destinationArray, commandID, messageID);
                break;

            case 0x3f: //63
            case 0x53: //83
                result = GetGprsFtpConfig(destinationArray, commandID, messageID);
                break;

            case 0x40: //64
            case 0x54: //84
                result = GetGprsProviderConfig(destinationArray, commandID, messageID);
                break;

            default:
                result = null;
        }
        return result;
    }
    
    
    private DeviceCommand GetGprsBaseConfig(byte[] command, byte cmd,int messageID){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", (cmd == 80 ? "getparam" : "setparam"));
        extendedInfo.set("param", cmd);
        extendedInfo.set("commandrow", messageID);        
        extendedInfo.set("mode", TeletrackProtocolA1.L4ToInt16(command, 0));
        extendedInfo.set("apnserver", TeletrackProtocolA1.L4BytesToString(command, 2, 0x19));
        extendedInfo.set("apnlogin", TeletrackProtocolA1.L4BytesToString(command, 0x1b, 10));
        extendedInfo.set("apnpassword", TeletrackProtocolA1.L4BytesToString(command, 0x25, 10));
        extendedInfo.set("dnsserver", TeletrackProtocolA1.L4BytesToString(command, 0x2f, 0x10));
        extendedInfo.set("dialnumber", TeletrackProtocolA1.L4BytesToString(command, 0x3f, 11));
        extendedInfo.set("gprslogin", TeletrackProtocolA1.L4BytesToString(command, 0x4a, 10));
        extendedInfo.set("gprspassword", TeletrackProtocolA1.L4BytesToString(command, 0x54, 10));
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;
    }

    private DeviceCommand GetGprsEmailConfig(byte[] command, byte cmd,int messageID){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", (cmd == 81 ? "getparam" : "setparam"));
        extendedInfo.set("param", cmd);
        extendedInfo.set("commandrow", messageID);
        
        extendedInfo.set("smtpserver", TeletrackProtocolA1.L4BytesToString(command, 0, 0x19));
        extendedInfo.set("smtplogin", TeletrackProtocolA1.L4BytesToString(command, 0x19, 10));
        extendedInfo.set("smtppassword", TeletrackProtocolA1.L4BytesToString(command, 0x23, 10));
        extendedInfo.set("pop3server", TeletrackProtocolA1.L4BytesToString(command, 0x2d, 0x19));
        extendedInfo.set("pop3login", TeletrackProtocolA1.L4BytesToString(command, 70, 10));
        extendedInfo.set("pop3password", TeletrackProtocolA1.L4BytesToString(command, 80, 10));
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;   
    }

    private DeviceCommand GetGprsFtpConfig(byte[] command, byte cmd,int messageID){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", (cmd == 83 ? "getparam" : "setparam"));
        extendedInfo.set("param", cmd);
        extendedInfo.set("commandrow", messageID);
        
        extendedInfo.set("server", TeletrackProtocolA1.L4BytesToString(command, 0, 0x19));
        extendedInfo.set("login", TeletrackProtocolA1.L4BytesToString(command, 0x19, 10));
        extendedInfo.set("password", TeletrackProtocolA1.L4BytesToString(command, 0x23, 10));
        extendedInfo.set("configpath", TeletrackProtocolA1.L4BytesToString(command, 0x2d, 20));
        extendedInfo.set("putpath", TeletrackProtocolA1.L4BytesToString(command, 0x41, 20));
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;  
    }

    private DeviceCommand GetGprsProviderConfig(byte[] command, byte cmd, int messageID) {
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", (cmd == 84 ? "getparam" : "setparam"));
        extendedInfo.set("param", cmd);
        extendedInfo.set("commandrow", messageID);
        
        extendedInfo.set("initstring", TeletrackProtocolA1.L4BytesToString(command, 0, 50));
        extendedInfo.set("domain", TeletrackProtocolA1.L4BytesToString(command, 50, 0x19));
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config; 
    }

    private DeviceCommand GetGprsSocketConfig(byte[] command, byte cmd, int messageID){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", (cmd == 82 ? "getparam" : "setparam"));
        extendedInfo.set("param", cmd);
        extendedInfo.set("commandrow", messageID);        
        extendedInfo.set("server", TeletrackProtocolA1.L4BytesToString(command, 0, 20));
        extendedInfo.set("port", TeletrackProtocolA1.L4ToInt16(command, 20));
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;  
    }

    private DeviceCommand GetPhoneNumberConfig(byte[] command, byte cmd, int messageID){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", (cmd == 42 ? "getparam" : "setparam"));
        extendedInfo.set("param", cmd);
        extendedInfo.set("commandrow", messageID);
        
        extendedInfo.set("numberaccept1", TeletrackProtocolA1.L4BytesToTelNumber(command, 0, 11));
        extendedInfo.set("numberaccept2", TeletrackProtocolA1.L4BytesToTelNumber(command, 11, 11));
        extendedInfo.set("numberaccept3", TeletrackProtocolA1.L4BytesToTelNumber(command, 0x16, 11));
        extendedInfo.set("numberdspt", TeletrackProtocolA1.L4BytesToTelNumber(command, 0x21, 11));
        extendedInfo.set("name1", TeletrackProtocolA1.L4BytesToString(command, 0x2d, 8));
        extendedInfo.set("name2", TeletrackProtocolA1.L4BytesToString(command, 0x35, 8));
        extendedInfo.set("name3", TeletrackProtocolA1.L4BytesToString(command, 0x3d, 8));
        extendedInfo.set("numberSOS", TeletrackProtocolA1.L4BytesToTelNumber(command, 0x45, 11));
            
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;  
    }

    private DeviceCommand GetSmsAddrConfig(byte[] command, byte cmd, int messageID){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", (cmd == 41 ? "getparam" : "setparam"));
        extendedInfo.set("param", cmd);
        extendedInfo.set("commandrow", messageID);
        
        extendedInfo.set("dsptemailgprs", TeletrackProtocolA1.L4BytesToString(command, 0, 30));
        extendedInfo.set("dsptemailsms", TeletrackProtocolA1.L4BytesToTelNumber(command, 30, 14));
        extendedInfo.set("smscentre", TeletrackProtocolA1.L4BytesToTelNumber(command, 0x2c, 11));
        extendedInfo.set("smsdspt", TeletrackProtocolA1.L4BytesToTelNumber(command, 0x37, 11));
        extendedInfo.set("smsemailgate", TeletrackProtocolA1.L4BytesToTelNumber(command, 0x42, 11));
            
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;    
    }

    private DeviceCommand GetUniqueConfig(byte[] command, byte cmd, int messageID){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", (cmd == 44 ? "getparam" : "setparam"));
        extendedInfo.set("param", cmd);
        extendedInfo.set("commandrow", messageID);        
        extendedInfo.set("dispatcherid", TeletrackProtocolA1.L4BytesToString(command, 0, 4));
        extendedInfo.set("password", TeletrackProtocolA1.L4BytesToString(command, 4, 8));
        extendedInfo.set("tmppassword", TeletrackProtocolA1.L4BytesToString(command, 12, 8));
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;   
    }

    private DeviceCommand GetZoneConfigConfirm(byte[] command, byte cmd, int messageID){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", "setparam");
        extendedInfo.set("param", cmd);
        extendedInfo.set("commandrow", messageID);     
        extendedInfo.set("zonemsgid", TeletrackProtocolA1.L4BytesToInt(command, 0));
        extendedInfo.set("zonestate", command[4]);
        extendedInfo.set("result", command[5]);
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config; 
    }
        
    private List<Position> GetDataGps(byte[] command, byte cmd, int messageID) {
        List<Position> positions = new LinkedList<Position>();
        short startIndex = 0;
        int WhatWrite = TeletrackProtocolA1.L4ToInt16(command, startIndex);
        startIndex = (short) (startIndex + 2);
        byte num2 = command[startIndex];
        startIndex = (short) (startIndex + 1);
        for (int i = 0; i < num2; i++){
            ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
            extendedInfo.set("whatwrite", WhatWrite);
            extendedInfo.set("command", "getparam");
            extendedInfo.set("param", cmd);
            extendedInfo.set("commandrow", messageID);
            
            Position position = new Position();
            position.setDeviceId(deviceId);
            position.setTableName(tableName);
            position.setImei(deviceImei);
            
            int Flags = 0;
            int Latitude = 0;
            int Longitude = 0;
            
            if (TeletrackProtocolA1.IsBitSetInMask(WhatWrite, (byte)0)){
                long time = TeletrackProtocolA1.L4BytesToInt(command, startIndex);
                startIndex = (short) (startIndex + 4);
                
                TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
                java.util.Date date= new java.util.Date((time*1000));
                position.setTime(date);

            }
            if (TeletrackProtocolA1.IsBitSetInMask(WhatWrite, (byte)1)){
                Latitude = TeletrackProtocolA1.L4BytesToInt(command, startIndex) * 10;
                startIndex = (short) (startIndex + 4);

                position.setLatitude(Latitude / 600000.0); 
            }
            if (TeletrackProtocolA1.IsBitSetInMask(WhatWrite, (byte)2)){
                Longitude = TeletrackProtocolA1.L4BytesToInt(command, startIndex) * 10;
                startIndex = (short) (startIndex + 4);

                position.setLongitude(Longitude / 600000.0);
            }
            if (TeletrackProtocolA1.IsBitSetInMask(WhatWrite, (byte)3)){
                int Altitude = command[startIndex] & 0xFF;
                position.setAltitude(Altitude + 0.0);
                startIndex = (short) (startIndex + 1);
            }
            if (TeletrackProtocolA1.IsBitSetInMask(WhatWrite, (byte)4)){
                position.setCourse(((command[startIndex] * 360) / 255.0));
                startIndex = (short) (startIndex + 1);
            }
            if (TeletrackProtocolA1.IsBitSetInMask(WhatWrite, (byte) 5)){
                position.setSpeed(command[startIndex] * 1.85);
                startIndex = (short) (startIndex + 1);
            }
            if (TeletrackProtocolA1.IsBitSetInMask(WhatWrite, (byte) 6)){
                extendedInfo.set("logid", TeletrackProtocolA1.L4BytesToInt(command, startIndex));
                startIndex = (short) (startIndex + 4);
            }
            if (TeletrackProtocolA1.IsBitSetInMask(WhatWrite, (byte) 7)){
                Flags = command[startIndex];
                extendedInfo.set("flags", Flags);
                startIndex = (short) (startIndex + 1);
            }
            if (TeletrackProtocolA1.IsBitSetInMask(WhatWrite, (byte) 8)){
                long events = TeletrackProtocolA1.L4BytesToInt(command, startIndex) & 0xFFFFFFFF;
                extendedInfo.set("events", events);
                startIndex = (short) (startIndex + 4);
            }
            if (TeletrackProtocolA1.IsBitSetInMask(WhatWrite, (byte) 9)){
                String sensors = "";  //8b                
                                
                sensors += String.format("%8s", Integer.toBinaryString(command[startIndex] & 0xFF)).replace(' ', '0');
                startIndex = (short) (startIndex + 1);
                sensors += String.format("%8s", Integer.toBinaryString(command[startIndex] & 0xFF)).replace(' ', '0');
                startIndex = (short) (startIndex + 1);
                sensors += String.format("%8s", Integer.toBinaryString(command[startIndex] & 0xFF)).replace(' ', '0');
                startIndex = (short) (startIndex + 1);
                sensors += String.format("%8s", Integer.toBinaryString(command[startIndex] & 0xFF)).replace(' ', '0');
                startIndex = (short) (startIndex + 1);
                sensors += String.format("%8s", Integer.toBinaryString(command[startIndex] & 0xFF)).replace(' ', '0');
                startIndex = (short) (startIndex + 1);
                sensors += String.format("%8s", Integer.toBinaryString(command[startIndex] & 0xFF)).replace(' ', '0');
                startIndex = (short) (startIndex + 1);
                sensors += String.format("%8s", Integer.toBinaryString(command[startIndex] & 0xFF)).replace(' ', '0');
                startIndex = (short) (startIndex + 1);
                sensors += String.format("%8s", Integer.toBinaryString(command[startIndex] & 0xFF)).replace(' ', '0');
                startIndex = (short) (startIndex + 1);
                
                Long sens = new BigInteger(sensors, 2).longValue();
                //Long sens = Long.parseLong(sensors, 2);                
                extendedInfo.set("sensors", Long.toUnsignedString(sens));
            }
            if (TeletrackProtocolA1.IsBitSetInMask(WhatWrite, (byte)10)){
                extendedInfo.set("counter1", TeletrackProtocolA1.L4ToInt16(command, startIndex) & 0xFFFF);
                startIndex = (short) (startIndex + 2);
                extendedInfo.set("counter2", TeletrackProtocolA1.L4ToInt16(command, startIndex) & 0xFFFF);
                startIndex = (short) (startIndex + 2);
                extendedInfo.set("counter3", TeletrackProtocolA1.L4ToInt16(command, startIndex) & 0xFFFF);
                startIndex = (short) (startIndex + 2);
                extendedInfo.set("counter4", TeletrackProtocolA1.L4ToInt16(command, startIndex) & 0xFFFF);
                startIndex = (short) (startIndex + 2);
            }
            if ((Math.abs(Longitude) > 0x66ff300) || (Math.abs(Latitude) > 0x337f980)){
                position.setValid(false);
            }
            else{
                position.setValid((Flags & 8) != 0);
            }
            position.setExtendedInfo(extendedInfo.getStyle(getDataManager().getStyleInfo()));
            
            positions.add(position);
        }
        return positions;
    }
    
    /*
    * Декодирование сообщения кофигурации событий 13, 23, 43 
    */
    private DeviceCommand GetEventConfig(byte[] command, byte cmd, int messageID){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", (cmd == 43 ? "getparam" : "setparam"));
        extendedInfo.set("param", cmd);
        extendedInfo.set("commandrow", messageID);        
        extendedInfo.set("speedchange", command[0] & 0xFF);        
        extendedInfo.set("coursebend", TeletrackProtocolA1.L4ToInt16(command, 1));
        extendedInfo.set("distance1", TeletrackProtocolA1.L4ToInt16(command, 3));
        extendedInfo.set("distance2", TeletrackProtocolA1.L4ToInt16(command, 5));
        for (int i = 0; i < 0x20; i++){
            extendedInfo.set("eventmask"+i, TeletrackProtocolA1.L4ToInt16(command, (i << 1) + 7));
        }
        extendedInfo.set("minspeed", TeletrackProtocolA1.L4ToInt16(command, 0x47));
        extendedInfo.set("timer1", TeletrackProtocolA1.L4ToInt16(command, 0x49));
        extendedInfo.set("timer2", TeletrackProtocolA1.L4ToInt16(command, 0x4b));
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;
    }

    private DeviceCommand GetIdConfig(byte[] command, byte cmd, int messageID) {
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", (cmd == 47 ? "getparam" : "setparam"));
        extendedInfo.set("param", cmd);
        extendedInfo.set("commandrow", messageID);        
        extendedInfo.set("devidshort", TeletrackProtocolA1.L4BytesToString(command,0, 4));
        extendedInfo.set("devidlong", TeletrackProtocolA1.L4BytesToString(command,4, 0x10));
        extendedInfo.set("moduleidgps", TeletrackProtocolA1.L4BytesToString(command, 20, 4));
        extendedInfo.set("moduleidgsm", TeletrackProtocolA1.L4BytesToString(command, 0x18, 4));
        extendedInfo.set("moduleidrf", TeletrackProtocolA1.L4BytesToString(command, 0x20, 4));
        extendedInfo.set("moduleidss", TeletrackProtocolA1.L4BytesToString(command, 0x24, 4));
        extendedInfo.set("verprotocollong", TeletrackProtocolA1.L4BytesToString(command, 40, 0x10));
        extendedInfo.set("verprotocolshort", TeletrackProtocolA1.L4BytesToString(command, 0x38, 2));
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;
    }

    public byte[] EncodeDataGpsQuery(DeviceCommand query, int commandID){
        String data = query.getData();
        //return new DataGpsQuery { 
        //ShortID = this.textBoxID_TT.Text, 
        
        
        //Идентификатор сообщения. 
        //Соответствует полю MessageCounter таблицы Messages в базе данных компании RCS. 
        //Позволяет четко определять порядок отправки-получения сообщений для заданного телетрека. 
        //При отправке сообщения телетреку из диспетчерского центра счетчик сообщений 
        //для данного телетрека инкрементируется и записывается в это поле. 
        //Телетрек после получения сообщения создает ответ и в поле 
        //MessageID подставляет значение MessageID принятого пакета. 
        //Теперь после получения диспетчерским центром подтверждения 
        //от телетрека становиться понятно, дошло сообщение или нет. 
        //Этот механизм позволяет избежать колизий, в случае отправки подряд 
        //нескольких сообщений, а дошло только одно и непонятно какое.
        //MessageID = this.cmdMsgCnt = (ushort) (this.cmdMsgCnt + 1), 
        
        //Маска, по которой определяется, какие критерии нужно использовать при 
        //выполнении анализа лог памяти телетрека. Расшифровка номеров бит: 
        //0 - последние записи 1 - последние секунды 2 - последние метры 
        //3 - максимальное кол-во сообщений 4 - события 5 - индексы 
        //6 - резерв 7 - резерв 8 - резерв 9 - резерв 
        //10 - интервал по времени 11 - интервал по скорости 
        //12 - интервал по широте 13 - интервал по долготе 
        //14 - интервал по высоте 15 - интервал по направлению 
        //16 - интервал по индексам 17 - флаги 
        //CheckMask = 1, 
        int CheckMask = Integer.parseInt(this.getDataManager().getQuantParametr(data,"checkcask"));
        
        //CommandID = CommandDescriptor.DataGpsQuery, 
        
        //Маска, в которой описано, какие поля структуры GPS данных следует передать. 
        //Расшифровка номеров бит маски: 0 - время 1 - широта 2 - долгота 
        //3 - высота 4 - направление 5 - скорость 6 - индекс лога (всегда равен 1) 
        //7 - флаги 8 - события 9 - датчики 10 - счетчики  
        int WhatSend = 0x3ff;
        
        int MaxSmsNumber = 1;
        int StartTime = 0;// Начало интервала времени, UnixTime 
        int EndTime = 0;// Конец интервала времени, UnixTime  
            
        int Events = 0;
        
        //LastRecords = 0x65 }; //101
        int LastRecords = Integer.parseInt(this.getDataManager().getQuantParametr(data,"lastrecords"));
        int LastTimes = 0;
        int LastMeters = 0;
        
        byte StartSpeed = 0;
        byte EndSpeed = 0;
        
        int StartLatitude = 0;
        int StartLongitude = 0;
        int EndLatitude = 0;
        int EndLongitude = 0;
        byte StartAltitude = 0;
        byte EndAltitude = 0;
        byte StartDirection = 0;
        byte EndDirection = 0;
        
        int StartLogId = 0;
        int EndLogId = 0;
        byte StartFlags = 0;
        byte EndFlags = 0;
        
        int LogId1 = 0;
        int LogId2 = 0;
        int LogId3 = 0;
        int LogId4 = 0;
        int LogId5 = 0;
        
        if (query == null){
            //throw new ArgumentNullException("query", "Не передан запрос GPS данных query");
            return null;
        }
        //if (!query.Validate(out str)){
            //throw new A1Exception(string.Format("A1_7. Не пройдена проверка правильности заполнения атрубутов сущности {0}: {1}.", query.GetType().Name, str));
        //}
        //query.CommandID = CommandDescriptor.DataGpsQuery;
        
        int id = (int) Integer.valueOf(query.getId().toString());
        byte[] destinationCommand = TeletrackProtocolA1.GetCommandLevel3Template((byte) commandID, id);
        int startIndex = 3;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UIntToBytes(CheckMask), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UIntToBytes(Events), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UIntToBytes(LastRecords), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UIntToBytes(LastTimes), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UIntToBytes(LastMeters), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UIntToBytes(MaxSmsNumber), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UIntToBytes(WhatSend), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4IntToBytes(StartTime), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(new byte[] { StartSpeed }, destinationCommand, startIndex, 1);
        startIndex++;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4IntToBytes(StartLatitude / 10), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4IntToBytes(StartLongitude / 10), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(new byte[] { StartAltitude }, destinationCommand, startIndex, 1);
        startIndex++;
        TeletrackProtocolA1.FillCommandAttribute(new byte[] { StartDirection }, destinationCommand, startIndex, 1);
        startIndex++;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4IntToBytes(StartLogId), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(new byte[] { StartFlags }, destinationCommand, startIndex, 1);
        startIndex++;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4IntToBytes(EndTime), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(new byte[] { EndSpeed }, destinationCommand, startIndex, 1);
        startIndex++;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4IntToBytes(EndLatitude / 10), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4IntToBytes(EndLongitude / 10), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(new byte[] { EndAltitude }, destinationCommand, startIndex, 1);
        startIndex++;
        TeletrackProtocolA1.FillCommandAttribute(new byte[] { EndDirection }, destinationCommand, startIndex, 1);
        startIndex++;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4IntToBytes(EndLogId), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(new byte[] { EndFlags }, destinationCommand, startIndex, 1);
        startIndex++;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4IntToBytes(LogId1), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4IntToBytes(LogId2), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4IntToBytes(LogId3), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4IntToBytes(LogId4), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4IntToBytes(LogId5), destinationCommand, startIndex, 4);
        
        byte[] dst2 = TeletrackProtocolA1.GetMessageLevel1(destinationCommand, deviceImei);
        return TeletrackProtocolA1.GetMessageLevel0(dst2, "");        
    }
    
    private byte[] EncodeConfigQuery(DeviceCommand query, int commandID){
        if (query == null){
            //throw new ArgumentNullException("query", "Не передан запрос кофигурации телефонных номеров");
            return null;
        }        
        int id = (int) Integer.valueOf(query.getId().toString());
        byte[] buffer = TeletrackProtocolA1.GetCommandLevel3Template((byte) (commandID & 0xFF), id);
        return TeletrackProtocolA1.GetMessageLevel0(TeletrackProtocolA1.GetMessageLevel1(buffer, deviceImei), "");
    }
    
    public byte[] EncodeDeviceReset(DeviceCommand query){
        if (query == null) {
            return null;
        }
        String messageToDriver = "CMD=SW_RESET";
        int id = (int) Integer.valueOf(query.getId().toString());
        byte[] destinationCommand = TeletrackProtocolA1.GetCommandLevel3Template((byte) 10, id);
        int startIndex = 3;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(messageToDriver, 0x4f), destinationCommand, startIndex, 80);
        return TeletrackProtocolA1.GetMessageLevel0(TeletrackProtocolA1.GetMessageLevel1(destinationCommand, deviceImei), "");
    }
    
    public byte[] EncodeMessageToDriver(DeviceCommand query, int commandID){
        if (query == null) {
            return null;
        }

        String data = query.getData();
        String messageToDriver = this.getDataManager().getQuantParametr(data,"value");
        int id = (int) Integer.valueOf(query.getId().toString());
        byte[] destinationCommand = TeletrackProtocolA1.GetCommandLevel3Template((byte) commandID, id);
        int startIndex = 3;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(messageToDriver, 0x4f), destinationCommand, startIndex, 80);

        return TeletrackProtocolA1.GetMessageLevel0(TeletrackProtocolA1.GetMessageLevel1(destinationCommand, deviceImei), "");
    }
    
    public byte[] EncodeSmsAddrConfigSet(DeviceCommand query, int commandID){
        if (query == null){
            //throw new ArgumentNullException("smsAddrConfig", "Не передана конфигурация SMS адресов smsAddrConfig");
            return null;
        }
        String data = query.getData();
        String DsptEmailGprs = this.getDataManager().getQuantParametr(data,"dsptemailgprs");
        String DsptEmailSMS = this.getDataManager().getQuantParametr(data,"dsptemailsms");
        String SmsCentre = this.getDataManager().getQuantParametr(data,"smscentre");
        String SmsDspt = this.getDataManager().getQuantParametr(data,"smsdspt");
        String SmsEmailGate = this.getDataManager().getQuantParametr(data,"smsemailgate");
        
        int id = (int) Integer.valueOf(query.getId().toString());
        byte[] destinationCommand = TeletrackProtocolA1.GetCommandLevel3Template((byte) commandID, id);
        int startIndex = 3;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(DsptEmailGprs, 30), destinationCommand, startIndex, 30);
        startIndex += 30;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(DsptEmailSMS, 14), destinationCommand, startIndex, 14);
        startIndex += 14;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4TelNumberToBytes(SmsCentre, 11), destinationCommand, startIndex, 11);
        startIndex += 11;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4TelNumberToBytes(SmsDspt, 11), destinationCommand, startIndex, 11);
        startIndex += 11;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4TelNumberToBytes(SmsEmailGate, 11), destinationCommand, startIndex, 11);
        
        return TeletrackProtocolA1.GetMessageLevel0(TeletrackProtocolA1.GetMessageLevel1(destinationCommand, deviceImei), "");
    }
    
    public byte[] EncodePhoneNumberConfigSet(DeviceCommand query, int commandID){
        if (query == null){
            //throw new ArgumentNullException("smsAddrConfig", "Не передана конфигурация SMS адресов smsAddrConfig");
            return null;
        }
        String data = query.getData();
        String NumberAccept1 = this.getDataManager().getQuantParametr(data,"numberaccept1");
        String NumberAccept2 = this.getDataManager().getQuantParametr(data,"numberaccept2");
        String NumberAccept3 = this.getDataManager().getQuantParametr(data,"numberaccept3");
        String NumberDspt = this.getDataManager().getQuantParametr(data,"numberdspt");
        String Name1 = this.getDataManager().getQuantParametr(data,"name1");
        String Name2 = this.getDataManager().getQuantParametr(data,"name2");
        String Name3 = this.getDataManager().getQuantParametr(data,"name3");
        String NumberSOS = this.getDataManager().getQuantParametr(data,"numbersos");
        
        int id = (int) Integer.valueOf(query.getId().toString());
        byte[] destinationCommand = TeletrackProtocolA1.GetCommandLevel3Template((byte) commandID, id);
        int startIndex = 3;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4TelNumberToBytes(NumberAccept1, 11), destinationCommand, startIndex, 11);
        startIndex += 11;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4TelNumberToBytes(NumberAccept2, 11), destinationCommand, startIndex, 11);
        startIndex += 11;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4TelNumberToBytes(NumberAccept3, 11), destinationCommand, startIndex, 11);
        startIndex += 11;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4TelNumberToBytes(NumberDspt, 11), destinationCommand, startIndex, 11);
        startIndex += 11;
        TeletrackProtocolA1.FillCommandAttribute(new byte[1], destinationCommand, startIndex, 1);
        startIndex++;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(Name1, 8), destinationCommand, startIndex, 8);
        startIndex += 8;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(Name2, 8), destinationCommand, startIndex, 8);
        startIndex += 8;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(Name3, 8), destinationCommand, startIndex, 8);
        startIndex += 8;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4TelNumberToBytes(NumberSOS, 11), destinationCommand, startIndex, 11);
        
        return TeletrackProtocolA1.GetMessageLevel0(TeletrackProtocolA1.GetMessageLevel1(destinationCommand, deviceImei), "");
    }
        
    public byte[] EncodeEventConfigSet(DeviceCommand query, int commandID){
        if (query == null){
            //throw new ArgumentNullException("phoneNumberConfig", "Не передана конфигурация событий eventConfig");
            return null;
        }
        String data = query.getData();
        byte SpeedChange = (byte)Integer.parseInt(this.getDataManager().getQuantParametr(data,"speedchange"));
        int CourseBend = Integer.parseInt(this.getDataManager().getQuantParametr(data,"coursebend"));
        int Distance1 = Integer.parseInt(this.getDataManager().getQuantParametr(data,"distance1"));
        int Distance2 = Integer.parseInt(this.getDataManager().getQuantParametr(data,"distance2"));
        int MinSpeed = Integer.parseInt(this.getDataManager().getQuantParametr(data,"minspeed"));
        int Timer1 = Integer.parseInt(this.getDataManager().getQuantParametr(data,"timer1"));
        int Timer2 = Integer.parseInt(this.getDataManager().getQuantParametr(data,"timer2"));
        
        int id = (int) Integer.valueOf(query.getId().toString());
        byte[] destinationCommand = TeletrackProtocolA1.GetCommandLevel3Template((byte) commandID, id);
        int startIndex = 3;
        TeletrackProtocolA1.FillCommandAttribute(new byte[] {SpeedChange}, destinationCommand, startIndex, 1);
        startIndex++;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UShortToBytes(CourseBend), destinationCommand, startIndex, 2);
        startIndex += 2;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UShortToBytes(Distance1), destinationCommand, startIndex, 2);
        startIndex += 2;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UShortToBytes(Distance2), destinationCommand, startIndex, 2);
        startIndex += 2;
        for (int i = 0; i < 0x20; i++){
            int EventMask = Integer.parseInt(this.getDataManager().getQuantParametr(data,"eventmask"+i));
            
            TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UShortToBytes(EventMask), destinationCommand, startIndex, 2);
            startIndex += 2;
        }
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UShortToBytes(MinSpeed), destinationCommand, startIndex, 2);
        startIndex += 2;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UShortToBytes(Timer1), destinationCommand, startIndex, 2);
        startIndex += 2;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UShortToBytes(Timer2), destinationCommand, startIndex, 2);
        startIndex += 2;
        TeletrackProtocolA1.FillCommandAttribute(new byte[2], destinationCommand, startIndex, 2);

        return TeletrackProtocolA1.GetMessageLevel0(TeletrackProtocolA1.GetMessageLevel1(destinationCommand, deviceImei), "");
    }
    
    public byte[] EncodeUniqueConfigSet(DeviceCommand query, int commandID) {
        if (query == null){
            //throw new ArgumentNullException("phoneNumberConfig", "Не передана конфигурация событий eventConfig");
            return null;
        }
        String data = query.getData();
        String DispatcherID = this.getDataManager().getQuantParametr(data,"dispatcherid");
        String Password = this.getDataManager().getQuantParametr(data,"password");
        String TmpPassword = this.getDataManager().getQuantParametr(data,"tmppassword");
        
        int id = (int) Integer.valueOf(query.getId().toString());
        byte[] destinationCommand = TeletrackProtocolA1.GetCommandLevel3Template((byte) commandID, id);
        
        int startIndex = 3;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(DispatcherID, 4), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(Password, 8), destinationCommand, startIndex, 8);
        startIndex += 8;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(TmpPassword, 8), destinationCommand, startIndex, 8);
       
        return TeletrackProtocolA1.GetMessageLevel0(TeletrackProtocolA1.GetMessageLevel1(destinationCommand, deviceImei), "");
    }
    
    public byte[] EncodeZoneConfigSet(DeviceCommand query, int commandID){
        /*
        string str;
        if (zoneConfig == null)
        {
            throw new ArgumentNullException("zoneConfig", "Не передана конфигурация контрольных зон zoneConfig");
        }
        if (!zoneConfig.Validate(out str))
        {
            throw new A1Exception(string.Format("A1_7. Не пройдена проверка правильности заполнения атрубутов сущности {0}: {1}.", zoneConfig.GetType().Name, str));
        }
        List<ZonePoint> list = new List<ZonePoint>();
        List<byte> list2 = new List<byte>();
        zoneConfig.CommandID = CommandDescriptor.ZoneConfigSet;
        
        
        byte[] buffer = GetCommandLevel3Template((byte) zoneConfig.CommandID, zoneConfig.MessageID);
        int index = 3;
        buffer[index] = 1;
        index++;
        buffer[index] = 0;
        index++;
        buffer[index] = (byte) zoneConfig.ZoneCount;
        index++;
        for (byte i = 0; i < 0x40; i = (byte) (i + 1)){
            if (zoneConfig.ZoneCount > i){
                foreach (ZonePoint point in zoneConfig.ZoneList[i].Points){
                    list.Add(point);
                }
                list2.Add(Util.GetMaskForZone(zoneConfig.ZoneList[i].Mask.EntryFlag, zoneConfig.ZoneList[i].Mask.ExitFlag, zoneConfig.ZoneList[i].Mask.InFlag, zoneConfig.ZoneList[i].Mask.OutFlag));
                if (list.Count > 0){
                    buffer[index] = (byte) (list.Count - 1);
                }
            }
            else{
                list2.Add(0);
            }
            index++;
        }
        zoneConfig.AddSource(GetMessageLevel0(GetMessageLevel1(buffer, zoneConfig.ShortID), ""));
        zoneConfig.AddMessage(Util.GetStringFromByteArray(zoneConfig.Source[0]));
        
        
        for (int j = list.Count; j < 0x100; j++){
            list.Add(new ZonePoint(0, 0));
        }
        for (byte k = 1; k <= 0x17; k = (byte) (k + 1)){
            buffer = GetCommandLevel3Template((byte) zoneConfig.CommandID, zoneConfig.MessageID);
            index = 3;
            buffer[index] = 3;
            index++;
            buffer[index] = k;
            index++;
            for (byte num5 = 0; num5 < 11; num5 = (byte) (num5 + 1)){
                byte num6 = (byte) (((k - 1) * 11) + num5);
                TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.IntToBytes(list[num6].Longitude / 10), buffer, index, 4);
                index += 4;
                TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.IntToBytes(list[num6].Latitude / 10), buffer, index, 4);
                index += 4;
            }
            zoneConfig.AddSource(GetMessageLevel0(GetMessageLevel1(buffer, zoneConfig.ShortID), ""));
            zoneConfig.AddMessage(Util.GetStringFromByteArray(zoneConfig.Source[k]));
        }
        buffer = GetCommandLevel3Template((byte) zoneConfig.CommandID, zoneConfig.MessageID);
        index = 3;
        buffer[index] = 4;
        index++;
        buffer[index] = 0;
        index++;
        for (byte m = 0; m < 3; m = (byte) (m + 1)){
            byte num8 = (byte) (0xfd + m);
            TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.IntToBytes(list[num8].Longitude / 10), buffer, index, 4);
            index += 4;
            TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.IntToBytes(list[num8].Latitude / 10), buffer, index, 4);
            index += 4;
        }
        for (byte n = 0; n < 0x40; n = (byte) (n + 1)){
            TeletrackProtocolA1.FillCommandAttribute(new byte[] { list2[n] }, buffer, index, 1);
            index++;
        }
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.IntToBytes(zoneConfig.ZoneMsgID), buffer, index, 4);
        index += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.IntToBytes(Util.CalculateZoneConfigCRC(zoneConfig)), buffer, index, 4);
        zoneConfig.AddSource(GetMessageLevel0(GetMessageLevel1(buffer, zoneConfig.ShortID), ""));
        zoneConfig.AddMessage(Util.GetStringFromByteArray(zoneConfig.Source[0x18]));
        return zoneConfig.Message.ToArray();
        */
        return null;
    }

    public byte[] EncodeIdConfigSet(DeviceCommand query, int commandID){
        if (query == null){
            return null;
        }
        String data = query.getData();
        String DevIdShort = this.getDataManager().getQuantParametr(data,"devidshort");
        String DevIdLong = this.getDataManager().getQuantParametr(data,"devidlong");
        String ModuleIdGps = this.getDataManager().getQuantParametr(data,"moduleidgps");
        String ModuleIdGsm = this.getDataManager().getQuantParametr(data,"moduleIdgsm");
        String ModuleIdMm = this.getDataManager().getQuantParametr(data,"moduleidmm");
        String ModuleIdRf = this.getDataManager().getQuantParametr(data,"moduleidrf");
        String ModuleIdSs = this.getDataManager().getQuantParametr(data,"moduleidss");
        String VerProtocolLong = this.getDataManager().getQuantParametr(data,"verprotocollong");
        String VerProtocolShort = this.getDataManager().getQuantParametr(data,"verprotocolshort");
                
        int id = (int) Integer.valueOf(query.getId().toString());
        byte[] destinationCommand = TeletrackProtocolA1.GetCommandLevel3Template((byte) commandID, id);
        
        int startIndex = 3;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(DevIdShort, 4), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(DevIdLong, 0x10), destinationCommand, startIndex, 0x10);
        startIndex += 0x10;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(ModuleIdGps, 4), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(ModuleIdGsm, 4), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(ModuleIdMm, 4), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(ModuleIdRf, 4), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(ModuleIdSs, 4), destinationCommand, startIndex, 4);
        startIndex += 4;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(VerProtocolLong, 0x10), destinationCommand, startIndex, 0x10);
        startIndex += 0x10;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(VerProtocolShort, 4), destinationCommand, startIndex, 4);

        return TeletrackProtocolA1.GetMessageLevel0(TeletrackProtocolA1.GetMessageLevel1(destinationCommand, deviceImei), "");
    }
        
    public byte[] EncodeGprsBaseConfigSet(DeviceCommand query, int commandID){
        if (query == null){
            return null;
        }
        String data = query.getData();
        int Mode = Integer.parseInt(this.getDataManager().getQuantParametr(data,"mode"));
        String ApnServer = this.getDataManager().getQuantParametr(data,"apnserver");
        String ApnLogin = this.getDataManager().getQuantParametr(data,"apnlogin");
        String ApnPassword = this.getDataManager().getQuantParametr(data,"apnpassword");
        String DnsServer = this.getDataManager().getQuantParametr(data,"dnsserver");
        String DialNumber = this.getDataManager().getQuantParametr(data,"dialnumber");
        String GprsLogin = this.getDataManager().getQuantParametr(data,"gprslogin");
        String GprsPassword = this.getDataManager().getQuantParametr(data,"gprspassword");
        
        int id = (int) Integer.valueOf(query.getId().toString());
        byte[] destinationCommand = TeletrackProtocolA1.GetCommandLevel3Template((byte) commandID, id);
        int startIndex = 3;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UShortToBytes(Mode), destinationCommand, startIndex, 2);
        startIndex += 2;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(ApnServer, 0x19), destinationCommand, startIndex, 0x19);
        startIndex += 0x19;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(ApnLogin, 10), destinationCommand, startIndex, 10);
        startIndex += 10;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(ApnPassword, 10), destinationCommand, startIndex, 10);
        startIndex += 10;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(DnsServer, 0x10), destinationCommand, startIndex, 0x10);
        startIndex += 0x10;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(DialNumber, 11), destinationCommand, startIndex, 11);
        startIndex += 11;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(GprsLogin, 10), destinationCommand, startIndex, 10);
        startIndex += 10;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(GprsPassword, 10), destinationCommand, startIndex, 10);

        return TeletrackProtocolA1.GetMessageLevel0(TeletrackProtocolA1.GetMessageLevel1(destinationCommand, deviceImei), "");
    }

    public byte[] EncodeGprsProviderConfigSet(DeviceCommand query, int commandID){
        if (query == null){
            return null;
        }
        String data = query.getData();
        String InitString = this.getDataManager().getQuantParametr(data,"initstring");
        String Domain = this.getDataManager().getQuantParametr(data,"domain");
        
        int id = (int) Integer.valueOf(query.getId().toString());
        byte[] destinationCommand = TeletrackProtocolA1.GetCommandLevel3Template((byte) commandID, id);
        int startIndex = 3;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(InitString, 50), destinationCommand, startIndex, 50);
        startIndex += 50;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(Domain, 0x19), destinationCommand, startIndex, 0x19);

        return TeletrackProtocolA1.GetMessageLevel0(TeletrackProtocolA1.GetMessageLevel1(destinationCommand, deviceImei), "");
        
    }

    public byte[] EncodeGprsEmailConfigSet(DeviceCommand query, int commandID){
        if (query == null){
            return null;
        }
        String data = query.getData();
        String SmtpServer = this.getDataManager().getQuantParametr(data,"smtpserver");
        String SmtpLogin = this.getDataManager().getQuantParametr(data,"smtplogin");
        String SmtpPassword = this.getDataManager().getQuantParametr(data,"smtppassword");
        String Pop3Server = this.getDataManager().getQuantParametr(data,"pop3server");
        String Pop3Login = this.getDataManager().getQuantParametr(data,"pop3login");
        String Pop3Password = this.getDataManager().getQuantParametr(data,"pop3password");
        
        int id = (int) Integer.valueOf(query.getId().toString());
        byte[] destinationCommand = TeletrackProtocolA1.GetCommandLevel3Template((byte) commandID, id);
        int startIndex = 3;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(SmtpServer, 0x19), destinationCommand, startIndex, 0x19);
        startIndex += 0x19;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(SmtpLogin, 10), destinationCommand, startIndex, 10);
        startIndex += 10;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(SmtpPassword, 10), destinationCommand, startIndex, 10);
        startIndex += 10;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(Pop3Server, 0x19), destinationCommand, startIndex, 0x19);
        startIndex += 0x19;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(Pop3Login, 10), destinationCommand, startIndex, 10);
        startIndex += 10;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(Pop3Password, 10), destinationCommand, startIndex, 10);

        return TeletrackProtocolA1.GetMessageLevel0(TeletrackProtocolA1.GetMessageLevel1(destinationCommand, deviceImei), "");
        
    }

    public byte[] EncodeGprsSocketConfigSet(DeviceCommand query, int commandID){
        if (query == null){
            return null;
        }
        String data = query.getData();
        String Server = this.getDataManager().getQuantParametr(data,"server");
        int Port = Integer.valueOf(this.getDataManager().getQuantParametr(data,"port"));

        int id = (int) Integer.valueOf(query.getId().toString());
        byte[] destinationCommand = TeletrackProtocolA1.GetCommandLevel3Template((byte) commandID, id);
        int startIndex = 3;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(Server, 20), destinationCommand, startIndex, 20);
        startIndex += 20;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UShortToBytes(Port), destinationCommand, startIndex, 2);
        return TeletrackProtocolA1.GetMessageLevel0(TeletrackProtocolA1.GetMessageLevel1(destinationCommand, deviceImei), "");        
    }

    public byte[] EncodeGprsFtpConfigSet(DeviceCommand query, int commandID){
        if (query == null){
            return null;
        }
        String data = query.getData();
        String Server = this.getDataManager().getQuantParametr(data,"server");
        String Login = this.getDataManager().getQuantParametr(data,"login");
        String Password = this.getDataManager().getQuantParametr(data,"password");
        String ConfigPath = this.getDataManager().getQuantParametr(data,"configpath");
        String PutPath = this.getDataManager().getQuantParametr(data,"putpath");

        int id = (int) Integer.valueOf(query.getId().toString());
        byte[] destinationCommand = TeletrackProtocolA1.GetCommandLevel3Template((byte) commandID, id);
        int startIndex = 3;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(Server, 0x19), destinationCommand, startIndex, 0x19);
        startIndex += 0x19;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(Login, 10), destinationCommand, startIndex, 10);
        startIndex += 10;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(Password, 10), destinationCommand, startIndex, 10);
        startIndex += 10;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(ConfigPath, 20), destinationCommand, startIndex, 20);
        startIndex += 20;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4StringToBytes(PutPath, 20), destinationCommand, startIndex, 20);
        return TeletrackProtocolA1.GetMessageLevel0(TeletrackProtocolA1.GetMessageLevel1(destinationCommand, deviceImei), "");        
    }
}
