/*
 */
package org.traccar.protocol;

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
import org.traccar.helper.ChannelBufferTools;
import org.traccar.helper.Crc;
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
    

    public TeletrackProtocolDecoder(DataManager dataManager, String protocol, Properties properties) {
        super(dataManager, protocol, properties);
        packetClear();
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
        int cmdId = Integer.parseInt(paramId);
        
        if("getparam".equals(cmdName) && (in(cmdId,0x1F,0x20,0x21,0x22,0x25,0x46,0x47,0x48,0x49,0x4A))){            
            sendData = EncodeConfigQuery(command, cmdId);
            String msg = TeletrackProtocolA1.GetStringFromByteArray(sendData);
            command.setCommand(msg);
        }
        else if("getparam".equals(cmdName) && (cmdId == 0x24)){ //DataGpsQuery
            //EncodeDataGpsQuery
            sendData = EncodeDataGpsQuery(command, cmdId);
            String msg = TeletrackProtocolA1.GetStringFromByteArray(sendData);
            command.setCommand(msg);
        }
        
        else if("setparam".equals(cmdName)){
            
        }
        
        if(sendData != null){
            channel.write(ChannelBuffers.wrappedBuffer(sendData));
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
        extendedInfo.set("logId", logId);

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
        sensors += String.format("%8s", Integer.toBinaryString(buf.readUnsignedByte() & 0xFF)).replace(' ', '0'); //Integer.toBinaryString((buf.readUnsignedByte()+256)%256); //1
        sensors += String.format("%8s", Integer.toBinaryString(buf.readUnsignedByte() & 0xFF)).replace(' ', '0'); //Integer.toBinaryString((buf.readUnsignedByte()+256)%256); //2
        sensors += String.format("%8s", Integer.toBinaryString(buf.readUnsignedByte() & 0xFF)).replace(' ', '0'); //Integer.toBinaryString((buf.readUnsignedByte()+256)%256); //3
        sensors += String.format("%8s", Integer.toBinaryString(buf.readUnsignedByte() & 0xFF)).replace(' ', '0'); //Integer.toBinaryString((buf.readUnsignedByte()+256)%256); //4
        sensors += String.format("%8s", Integer.toBinaryString(buf.readUnsignedByte() & 0xFF)).replace(' ', '0'); //Integer.toBinaryString((buf.readUnsignedByte()+256)%256); //5
        sensors += String.format("%8s", Integer.toBinaryString(buf.readUnsignedByte() & 0xFF)).replace(' ', '0'); //Integer.toBinaryString((buf.readUnsignedByte()+256)%256); //6
        sensors += String.format("%8s", Integer.toBinaryString(buf.readUnsignedByte() & 0xFF)).replace(' ', '0'); //Integer.toBinaryString((buf.readUnsignedByte()+256)%256); //7
        sensors += String.format("%8s", Integer.toBinaryString(buf.readUnsignedByte() & 0xFF)).replace(' ', '0'); //Integer.toBinaryString((buf.readUnsignedByte()+256)%256); //8
        extendedInfo.set("sensors", sensors);

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
        
        //Level1Converter
        int num2 = TeletrackProtocolA1.L1SymbolToValue(destinationArray[2]) << 2;
        if (num2 != 0x88)
        {
            //throw new A1Exception(string.Format(
            //"A1_6. Указанная длина {0} в пакете уровня LEVEL1 не соответствует ожиданиям - {1}.", num2, (byte) 0x88));
            return null;
        }
        //
        byte[] buffer2 = new byte[2];
        //Array.Copy(destinationArray, 0, buffer2, 0, 2);
        System.arraycopy(destinationArray, 0, buffer2, 0, buffer2.length );
        //Level1Converter
        //L1BytesToString(buffer2);
        //Level1Converter
        short num3 = TeletrackProtocolA1.L1SymbolToValue(destinationArray[7]);
        //Util
        byte num4 = TeletrackProtocolA1.CalculateLevel1CRC(destinationArray, 0, num2);
        if(num3 != num4){
            //throw new A1Exception(string.Format(
            //"A1_4. CRC не совпадает. Значение указанное в пакете {0}, расчитанное - {1}.", num3, num4));
        }
        byte[] buffer3 = new byte[4];
        //Array.Copy(destinationArray, 3, buffer3, 0, 4);
        System.arraycopy(destinationArray, 3, buffer3, 0, 4);
        byte[] buffer4 = new byte[0x88];
        //Array.Copy(destinationArray, 8, buffer4, 0, 0x88);
        System.arraycopy(destinationArray, 8, buffer4, 0, 0x88);
        
        Object description = DecodeLevel3Message(TeletrackProtocolA1.L1Decode6BitTo8(buffer4));
        String ShortID = TeletrackProtocolA1.L1BytesToString(buffer3);
        //description.Source = message;
        
        if (description instanceof DeviceCommand){
            byte[] Source = new byte[160]; //144        
            buf.getBytes(0, Source, 0, 160);
            ((DeviceCommand)description).setCommand(TeletrackProtocolA1.GetStringFromByteArray(Source));
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
                parseMultiBinLocation(channel, buf);
                packetClear();
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
            //
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
            
            buf.skipBytes(ATR_LENGTH);
            //Признак пакета 4 байта
            
            short res = buf.readUnsignedByte();
            //Код ответа 1 байт
            //0x00 – команда принята.
            //0x01 – ошибка обработки команды.
            
            //extendedInfo.set("command", "getparam");
            //extendedInfo.set("param", paramId);
            //extendedInfo.set("isset", isSet);   
        }
        else if("A1Packet".equals(PacketID)){ //A1            
            return parseA1(channel, buf);
            
        }
        // || "%%PB".equals(packetId)
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
            //throw new A1Exception(string.Format(
            //"A1_1. Длина пакета на уровне {0} не соответствует требованиям протокола. Ожидалось {1} байт, принято - {2}.", "LEVEL3", (byte) 0x66, command.Length));
        }
        byte commandID = command[0];
        //Level4Converter.
        int messageID = TeletrackProtocolA1.L4ToInt16(command, 1); //BytesToUShort(command, 1);
        byte[] destinationArray = new byte[0x63];
        //Array.Copy(command, 3, destinationArray, 0, 0x63);
        System.arraycopy(command, 3, destinationArray, 0, 0x63);
        
        
        switch (commandID){
            case 0x15:
            case 0x29:
                result = GetSmsAddrConfig(destinationArray, commandID);
                break;

            case 0x16:
            case 0x2a:
                result = GetPhoneNumberConfig(destinationArray, commandID);
                break;
            case 0x0D://13: //EventConfigSet
            case 0x17: //EventConfigConfirm ,
            //EventConfigQuery = 0x21,
            case 0x2b: //EventConfigAnswer
                result = GetEventConfig(destinationArray, commandID);
                break;

            case 0x18:
            case 0x2c:
                result = GetUniqueConfig(destinationArray, commandID);
                break;

            case 0x19:
                result = GetZoneConfigConfirm(destinationArray, commandID);
                break;

            case 0x1b:
            case 0x2f:
                result = GetIdConfig(destinationArray, commandID);                
                break;

            case 0x2e:
            case 0x31:
                result = GetDataGps(destinationArray, commandID);
                break;

            case 60:
            case 80:
                result = GetGprsBaseConfig(destinationArray, commandID);
                break;

            case 0x3d:
            case 0x51:
                result = GetGprsEmailConfig(destinationArray, commandID);
                break;

            case 0x3e:
            case 0x52:
                result = GetGprsSocketConfig(destinationArray, commandID);
                break;

            case 0x3f:
            case 0x53:
                result = GetGprsFtpConfig(destinationArray, commandID);
                break;

            case 0x40:
            case 0x54:
                result = GetGprsProviderConfig(destinationArray, commandID);
                break;

            default:
                result = null;
                //throw new A1Exception(string.Format(
                //"A1_5. От телетрека получена неизвестная команда с идентификатором: {0}.", num));
        }
        
        //smsAddrConfig.CommandID = (CommandDescriptor) commandID;
        //smsAddrConfig.MessageID = messageID;
        return result;
    }
    
    
    private DeviceCommand GetGprsBaseConfig(byte[] command, byte cmd){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", cmd);
        
        //return new GprsBaseConfig { 
        //    Mode = Level4Converter.BytesToUShort(command, 0), 
        extendedInfo.set("Mode", TeletrackProtocolA1.L4ToInt16(command, 0));
        //    ApnServer = Level4Converter.BytesToString(command, 2, 0x19), 
        extendedInfo.set("ApnServer", TeletrackProtocolA1.L4BytesToString(command, 2, 0x19));
        //    ApnLogin = Level4Converter.BytesToString(command, 0x1b, 10), 
        extendedInfo.set("ApnLogin", TeletrackProtocolA1.L4BytesToString(command, 0x1b, 10));
        //    ApnPassword = Level4Converter.BytesToString(command, 0x25, 10), 
        extendedInfo.set("ApnPassword", TeletrackProtocolA1.L4BytesToString(command, 0x25, 1));
        //    DnsServer = Level4Converter.BytesToString(command, 0x2f, 0x10),
        extendedInfo.set("DnsServer", TeletrackProtocolA1.L4BytesToString(command, 0x2f, 0x10));
        //    DialNumber = Level4Converter.BytesToString(command, 0x3f, 11), 
        extendedInfo.set("DialNumber", TeletrackProtocolA1.L4BytesToString(command, 0x3f, 11));
        //    GprsLogin = Level4Converter.BytesToString(command, 0x4a, 10), 
        extendedInfo.set("GprsLogin", TeletrackProtocolA1.L4BytesToString(command, 0x4a, 10));
        //    GprsPassword = Level4Converter.BytesToString(command, 0x54, 10) 
        extendedInfo.set("GprsPassword", TeletrackProtocolA1.L4BytesToString(command, 0x54, 10));
        //};
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;
    }

    private DeviceCommand GetGprsEmailConfig(byte[] command, byte cmd){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", cmd);
        
        //return new GprsEmailConfig { 
        //SmtpServer = Level4Converter.BytesToString(command, 0, 0x19), 
        extendedInfo.set("SmtpServer", TeletrackProtocolA1.L4BytesToString(command, 0, 0x19));
        //SmtpLogin = Level4Converter.BytesToString(command, 0x19, 10), 
        extendedInfo.set("SmtpLogin", TeletrackProtocolA1.L4BytesToString(command, 0x19, 10));
        //SmtpPassword = Level4Converter.BytesToString(command, 0x23, 10), 
        extendedInfo.set("SmtpPassword", TeletrackProtocolA1.L4BytesToString(command, 0x23, 10));
        //Pop3Server = Level4Converter.BytesToString(command, 0x2d, 0x19), 
        extendedInfo.set("Pop3Server", TeletrackProtocolA1.L4BytesToString(command, 0x2d, 0x19));
        //Pop3Login = Level4Converter.BytesToString(command, 70, 10), 
        extendedInfo.set("Pop3Login", TeletrackProtocolA1.L4BytesToString(command, 70, 10));
        //Pop3Password = Level4Converter.BytesToString(command, 80, 10) };
        extendedInfo.set("Pop3Password", TeletrackProtocolA1.L4BytesToString(command, 80, 10));
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;   
    }

    private DeviceCommand GetGprsFtpConfig(byte[] command, byte cmd){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", cmd);
        //return new GprsFtpConfig { 
        //Server = Level4Converter.BytesToString(command, 0, 0x19), 
        extendedInfo.set("Server", TeletrackProtocolA1.L4BytesToString(command, 0, 0x19));
        //Login = Level4Converter.BytesToString(command, 0x19, 10), 
        extendedInfo.set("Login", TeletrackProtocolA1.L4BytesToString(command, 0x19, 10));
        //Password = Level4Converter.BytesToString(command, 0x23, 10), 
        extendedInfo.set("Password", TeletrackProtocolA1.L4BytesToString(command, 0x23, 10));
        //ConfigPath = Level4Converter.BytesToString(command, 0x2d, 20), 
        extendedInfo.set("ConfigPath", TeletrackProtocolA1.L4BytesToString(command, 0x2d, 20));
        //PutPath = Level4Converter.BytesToString(command, 0x41, 20) };
        extendedInfo.set("PutPath", TeletrackProtocolA1.L4BytesToString(command, 0x41, 20));
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;  
    }

    private DeviceCommand GetGprsProviderConfig(byte[] command, byte cmd) {
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", cmd);
        //return new GprsProviderConfig { 
        //InitString = Level4Converter.BytesToString(command, 0, 50), 
        extendedInfo.set("InitString", TeletrackProtocolA1.L4BytesToString(command, 0, 50));
        //Domain = Level4Converter.BytesToString(command, 50, 0x19) };
        extendedInfo.set("Domain", TeletrackProtocolA1.L4BytesToString(command, 50, 0x19));
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config; 
    }

    private DeviceCommand GetGprsSocketConfig(byte[] command, byte cmd){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", cmd);
        //return new GprsSocketConfig { 
        //Server = Level4Converter.BytesToString(command, 0, 20), 
        extendedInfo.set("Server", TeletrackProtocolA1.L4BytesToString(command, 0, 20));
        //Port = Level4Converter.BytesToUShort(command, 20) };
        extendedInfo.set("Port", TeletrackProtocolA1.L4ToInt16(command, 20));
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;  
    }

    private DeviceCommand GetPhoneNumberConfig(byte[] command, byte cmd){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", cmd);
        
        //return new PhoneNumberConfig { 
        //NumberAccept1 = Level4Converter.BytesToTelNumber(command, 0, 11),
        extendedInfo.set("NumberAccept1", TeletrackProtocolA1.L4BytesToTelNumber(command, 0, 11));
        //NumberAccept2 = Level4Converter.BytesToTelNumber(command, 11, 11), 
        extendedInfo.set("NumberAccept2", TeletrackProtocolA1.L4BytesToTelNumber(command, 11, 11));
        //NumberAccept3 = Level4Converter.BytesToTelNumber(command, 0x16, 11), 
        extendedInfo.set("NumberAccept3", TeletrackProtocolA1.L4BytesToTelNumber(command, 0x16, 11));
        //NumberDspt = Level4Converter.BytesToTelNumber(command, 0x21, 11), 
        extendedInfo.set("NumberDspt", TeletrackProtocolA1.L4BytesToTelNumber(command, 0x21, 11));
        //Name1 = Level4Converter.BytesToString(command, 0x2d, 8), 
        extendedInfo.set("Name1", TeletrackProtocolA1.L4BytesToString(command, 0x2d, 8));
        //Name2 = Level4Converter.BytesToString(command, 0x35, 8), 
        extendedInfo.set("Name2", TeletrackProtocolA1.L4BytesToString(command, 0x35, 8));
        //Name3 = Level4Converter.BytesToString(command, 0x3d, 8), 
        extendedInfo.set("Name3", TeletrackProtocolA1.L4BytesToString(command, 0x3d, 8));
        //NumberSOS = Level4Converter.BytesToTelNumber(command, 0x45, 11) };
        extendedInfo.set("NumberSOS", TeletrackProtocolA1.L4BytesToTelNumber(command, 0x45, 11));
            
        //config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;  
    }

    private DeviceCommand GetSmsAddrConfig(byte[] command, byte cmd){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", cmd);
        //return new SmsAddrConfig { 
        //DsptEmailGprs = Level4Converter.BytesToString(command, 0, 30), 
        extendedInfo.set("DsptEmailGprs", TeletrackProtocolA1.L4BytesToString(command, 0, 30));
        //DsptEmailSMS = Level4Converter.BytesToString(command, 30, 14), 
        extendedInfo.set("DsptEmailSMS", TeletrackProtocolA1.L4BytesToTelNumber(command, 30, 14));
        //SmsCentre = Level4Converter.BytesToTelNumber(command, 0x2c, 11), 
        extendedInfo.set("SmsCentre", TeletrackProtocolA1.L4BytesToTelNumber(command, 0x2c, 11));
        //SmsDspt = Level4Converter.BytesToTelNumber(command, 0x37, 11), 
        extendedInfo.set("SmsDspt", TeletrackProtocolA1.L4BytesToTelNumber(command, 0x37, 11));
        //SmsEmailGate = Level4Converter.BytesToTelNumber(command, 0x42, 11) };
        extendedInfo.set("SmsEmailGate", TeletrackProtocolA1.L4BytesToTelNumber(command, 0x42, 11));
            
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;    
    }

    private DeviceCommand GetUniqueConfig(byte[] command, byte cmd){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", cmd);
        //return new UniqueConfig { 
        //DispatcherID = Level4Converter.BytesToString(command, 0, 4), 
        extendedInfo.set("DispatcherID", TeletrackProtocolA1.L4BytesToString(command, 0, 4));
        //Password = Level4Converter.BytesToString(command, 4, 8), 
        extendedInfo.set("Password", TeletrackProtocolA1.L4BytesToString(command, 4, 8));
        //TmpPassword = Level4Converter.BytesToString(command, 12, 8) };
        extendedInfo.set("TmpPassword", TeletrackProtocolA1.L4BytesToString(command, 12, 8));
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;   
    }

    private DeviceCommand GetZoneConfigConfirm(byte[] command, byte cmd){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", cmd);        
        //return new ZoneConfigConfirm { 
        //ZoneMsgID = Level4Converter.BytesToInt(command, 0), 
        extendedInfo.set("ZoneMsgID", TeletrackProtocolA1.L4BytesToInt(command, 0));
        //ZoneState = command[4], 
        extendedInfo.set("ZoneState", command[4]);
        //Result = command[5] };
        extendedInfo.set("Result", command[5]);
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config; 
    }
        
    private List<Position> GetDataGps(byte[] command, byte cmd) {
            //DataGpsAnswer answer = new DataGpsAnswer();
        List<Position> positions = new LinkedList<Position>();
        short startIndex = 0;
        int WhatWrite = TeletrackProtocolA1.L4ToInt16(command, startIndex);
        startIndex = (short) (startIndex + 2);
        byte num2 = command[startIndex];
        startIndex = (short) (startIndex + 1);
        for (int i = 0; i < num2; i++){
                //DataGps data = new DataGps();
            ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
            extendedInfo.set("WhatWrite", WhatWrite);
            extendedInfo.set("command", cmd);
            
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
                extendedInfo.set("logId", TeletrackProtocolA1.L4BytesToInt(command, startIndex));
                startIndex = (short) (startIndex + 4);
            }
            if (TeletrackProtocolA1.IsBitSetInMask(WhatWrite, (byte) 7)){
                Flags = command[startIndex];
                extendedInfo.set("Flags", Flags);
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
                
                extendedInfo.set("sensors", sensors);
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
            positions.add(position);
        }
        return positions;
    }
    
    /*
    * Декодирование сообщения кофигурации событий 13, 23, 43 
    */
    private DeviceCommand GetEventConfig(byte[] command, byte cmd){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", cmd);
        //extendedInfo.set("commandName", );
        
        //extendedInfo.set("codec", codec);
        extendedInfo.set("SpeedChange", command[0] & 0xFF);        
        extendedInfo.set("CourseBend", TeletrackProtocolA1.L4ToInt16(command, 1));
        extendedInfo.set("Distance1", TeletrackProtocolA1.L4ToInt16(command, 3));
        extendedInfo.set("Distance2", TeletrackProtocolA1.L4ToInt16(command, 5));
        
        
        for (int i = 0; i < 0x20; i++){
            extendedInfo.set("EventMask"+i, TeletrackProtocolA1.L4ToInt16(command, (i << 1) + 7));
        }
        
        extendedInfo.set("MinSpeed", TeletrackProtocolA1.L4ToInt16(command, 0x47));
        extendedInfo.set("Timer1", TeletrackProtocolA1.L4ToInt16(command, 0x49));
        extendedInfo.set("Timer2", TeletrackProtocolA1.L4ToInt16(command, 0x4b));
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;
    }

    private DeviceCommand GetIdConfig(byte[] command, byte cmd) {
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", cmd);
        //return new IdConfig { 
        //DevIdShort = Level4Converter.BytesToString(command, 0, 4), 
        extendedInfo.set("DevIdShort", TeletrackProtocolA1.L4BytesToString(command,0, 4));
        //DevIdLong = Level4Converter.BytesToString(command, 4, 0x10), 
        extendedInfo.set("DevIdLong", TeletrackProtocolA1.L4BytesToString(command,4, 0x10));
        //ModuleIdGps = Level4Converter.BytesToString(command, 20, 4), 
        extendedInfo.set("ModuleIdGps", TeletrackProtocolA1.L4BytesToString(command, 20, 4));
        //ModuleIdGsm = Level4Converter.BytesToString(command, 0x18, 4), 
        extendedInfo.set("ModuleIdGsm", TeletrackProtocolA1.L4BytesToString(command, 0x18, 4));
        //ModuleIdRf = Level4Converter.BytesToString(command, 0x20, 4), 
        extendedInfo.set("ModuleIdRf", TeletrackProtocolA1.L4BytesToString(command, 0x20, 4));
        //ModuleIdSs = Level4Converter.BytesToString(command, 0x24, 4), 
        extendedInfo.set("ModuleIdSs", TeletrackProtocolA1.L4BytesToString(command, 0x24, 4));
        //VerProtocolLong = Level4Converter.BytesToString(command, 40, 0x10), 
        extendedInfo.set("VerProtocolLong", TeletrackProtocolA1.L4BytesToString(command, 40, 0x10));
        //VerProtocolShort = Level4Converter.BytesToString(command, 0x38, 2) };
        extendedInfo.set("VerProtocolShort", TeletrackProtocolA1.L4BytesToString(command, 0x38, 2));
        
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
        int CheckMask = Integer.parseInt(this.getDataManager().getQuantParametr(data,"CheckMask"));
        
        //CommandID = CommandDescriptor.DataGpsQuery, 
        
        //Маска, в которой описано, какие поля структуры GPS данных следует передать. 
        //Расшифровка номеров бит маски: 0 - время 1 - широта 2 - долгота 
        //3 - высота 4 - направление 5 - скорость 6 - индекс лога (всегда равен 1) 
        //7 - флаги 8 - события 9 - датчики 10 - счетчики  
        //WhatSend = 0x3ff, 
        
        //MaxSmsNumber = 1, 
        //StartTime = 0, Начало интервала времени, UnixTime 
        //EndTime=0, Конец интервала времени, UnixTime  
        //EndTime = 0, 
        //LastRecords = 0x65 };    
        
        String str;
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
        //byte[] destinationCommand = TeletrackProtocolA1.GetCommandLevel3Template((byte) query.CommandID, query.MessageID);
        int startIndex = 3;
        TeletrackProtocolA1.FillCommandAttribute(TeletrackProtocolA1.L4UIntToBytes(CheckMask), destinationCommand, startIndex, 4);
        startIndex += 4;
        /*Util.FillCommandAttribute(Level4Converter.UIntToBytes(query.Events), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(Level4Converter.UIntToBytes(query.LastRecords), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(Level4Converter.UIntToBytes(query.LastTimes), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(Level4Converter.UIntToBytes(query.LastMeters), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(Level4Converter.UIntToBytes(query.MaxSmsNumber), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(Level4Converter.UIntToBytes(query.WhatSend), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(Level4Converter.IntToBytes(query.StartTime), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(new byte[] { query.StartSpeed }, destinationCommand, startIndex, 1);
        startIndex++;
        Util.FillCommandAttribute(Level4Converter.IntToBytes(query.StartLatitude / 10), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(Level4Converter.IntToBytes(query.StartLongitude / 10), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(new byte[] { query.StartAltitude }, destinationCommand, startIndex, 1);
        startIndex++;
        Util.FillCommandAttribute(new byte[] { query.StartDirection }, destinationCommand, startIndex, 1);
        startIndex++;
        Util.FillCommandAttribute(Level4Converter.IntToBytes(query.StartLogId), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(new byte[] { query.StartFlags }, destinationCommand, startIndex, 1);
        startIndex++;
        Util.FillCommandAttribute(Level4Converter.IntToBytes(query.EndTime), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(new byte[] { query.EndSpeed }, destinationCommand, startIndex, 1);
        startIndex++;
        Util.FillCommandAttribute(Level4Converter.IntToBytes(query.EndLatitude / 10), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(Level4Converter.IntToBytes(query.EndLongitude / 10), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(new byte[] { query.EndAltitude }, destinationCommand, startIndex, 1);
        startIndex++;
        Util.FillCommandAttribute(new byte[] { query.EndDirection }, destinationCommand, startIndex, 1);
        startIndex++;
        Util.FillCommandAttribute(Level4Converter.IntToBytes(query.EndLogId), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(new byte[] { query.EndFlags }, destinationCommand, startIndex, 1);
        startIndex++;
        Util.FillCommandAttribute(Level4Converter.IntToBytes(query.LogId1), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(Level4Converter.IntToBytes(query.LogId2), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(Level4Converter.IntToBytes(query.LogId3), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(Level4Converter.IntToBytes(query.LogId4), destinationCommand, startIndex, 4);
        startIndex += 4;
        Util.FillCommandAttribute(Level4Converter.IntToBytes(query.LogId5), destinationCommand, startIndex, 4);
        query.Source = GetMessageLevel0(GetMessageLevel1(destinationCommand, query.ShortID), "");
        query.Message = Util.GetStringFromByteArray(query.Source);
        return query.Message;
                */
        return null;
    }
    
    private byte[] EncodeConfigQuery(DeviceCommand query, int commandID){
        if (query == null){
            //throw new ArgumentNullException("query", "Не передан запрос кофигурации телефонных номеров");
            return null;
        }
        
        //int CommandID = 0x21;//  //CommandDescriptor.EventConfigQuery;
        int id = (int) Integer.valueOf(query.getId().toString());
        byte[] buffer = TeletrackProtocolA1.GetCommandLevel3Template((byte) commandID, id);
        return TeletrackProtocolA1.GetMessageLevel0(TeletrackProtocolA1.GetMessageLevel1(buffer, deviceImei), "");
        //String Message = TeletrackProtocolA1.GetStringFromByteArray(Source);
        //return Message;
        
    }
    
    public static <T> boolean in(T value, T... list) {
        for (T item : list) {
            if (value.equals(item))
                return true;
        }
        return false;
    }
}
