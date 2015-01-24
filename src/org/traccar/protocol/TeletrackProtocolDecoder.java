/*
 */
package org.traccar.protocol;

import java.nio.charset.Charset;
import java.sql.SQLException;
import java.util.Date;
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
    
    private int BIN_LENGTH = 0x20; //32
    private int ATR_LENGTH = 4;
    
    private int A1_ATR_LENGTH = 3;
    private int A1_EMAIL_SIZE = 13;
    
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
            
            sendCommand(channel);
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
        //
        int count = 7; //        
        //Не больше 4х в пакете
        int respCmd = (commands.size() > 3 ? 4 : commands.size());
        byte[] sendData = null;
        
        String styleInfo = this.getDataManager().getStyleInfo();
        
        for (int i = 0; i < respCmd; i++) {
            DeviceCommand command = commands.get(i);
            /*
            if((command.getCommand() != null) && (!"".equals(command.getCommand()))){ //command hex
                String cmd = command.getCommand();
                if(sendData == null){                    
                    sendData = ChannelBufferTools.convertHexString(cmd);
                } else {
                    sendData = ChannelBufferTools.mergeArray(sendData, ChannelBufferTools.convertHexString(cmd));
                }
                
            }
            else if (command.getData() != null){//command params     
                //
                String data = command.getData();
                if(styleInfo.equals("quant")){
                    String cmdName = this.getDataManager().getQuantParametr(data,"command");
                    String paramId = this.getDataManager().getQuantParametr(data,"param");
                    String paramValue = this.getDataManager().getQuantParametr(data,"value");
                    
                    if(cmdName != null){
                        byte[] sData = getBuildConfig(cmdName, (paramId == null ? 0 : Integer.parseInt(paramId)), paramValue);
                        
                        command.setCommand(ChannelBufferTools.readHexString(sData));                        
                        if(sendData == null){
                            sendData = sData;
                        } else {
                            sendData = ChannelBufferTools.mergeArray(sendData, sData);
                        }
                    }
                }
            }*/
        }
        if(sendData != null) {
            int cmdLen = sendData.length; //(cmd.length()/2);
            ChannelBuffer response2 = ChannelBuffers.directBuffer(count + cmdLen);
            //1 - 2 байта = 0x0000
            response2.writeShort(0x0000);
            //2 - Длина данных 2 байта без CRC16 = 0x04
            response2.writeShort(cmdLen + 1);
            //Данные
            //3 - Кол-во пакетов 1 байт = 0x01
            response2.writeByte(respCmd);
            //4
            response2.writeBytes(sendData);
            //6 - CRC16 2 байта (с №3 ПО №5 включительно) =             
            response2.writeShort(Crc.crc16_A001(response2.toByteBuffer(4, cmdLen + 1)));
            channel.write(response2);
            Log.debug("Response=" + ChannelBufferTools.readHexString(response2, (count + cmdLen) * 2));
        }
        
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
    
    /*
    * Разбор команды от устройства
    */
    private List<DeviceCommand> parseCommand(Channel channel, ChannelBuffer buf){
        List<DeviceCommand> commands = new LinkedList<DeviceCommand>();      
        
        /*
        buf.skipBytes(2); //
        buf.readUnsignedShort(); // data length
        int count = buf.readUnsignedByte(); // count
        
          

        for(int i = 0; i < count; i++){
            ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
            DeviceCommand command = new DeviceCommand();
            command.setDeviceId(deviceId);
            command.setImei(deviceImei);
            
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
                extendedInfo.set("command", "getparam");
                extendedInfo.set("param", paramId);
                extendedInfo.set("value", paramVal);
                commandStart = length + 4;
                commandLength = length + 4;
            }
            else if(codec == 37){//Ответ треккера на конфигурационный пакет – установка значения параметра
                int paramId = buf.readUnsignedShort();
                int isSet = buf.readUnsignedByte(); //0 – неверный параметр, 255 – параметр установлен
                extendedInfo.set("command", "getparam");
                extendedInfo.set("param", paramId);
                extendedInfo.set("isset", isSet);                
                commandStart = 4;
                commandLength = 4;
            }
            else if(codec == 41){//Ответ на пакет с командой от сервера
                short cmd = buf.readUnsignedByte();
                short length = buf.readUnsignedByte();   
                extendedInfo.set("command", "command");
                extendedInfo.set("param", getCommndParam((byte)cmd));
                String val= getCommandValue(buf, (byte)cmd, length);
                if(!"".equals(val)){
                    extendedInfo.set("value", val);
                }
            }
            else if(codec == 42){//Ответ на неподдерживаемую команду от сервера
                short cmd = buf.readUnsignedByte();
                extendedInfo.set("command", "command");
                extendedInfo.set("param", getCommndParam((byte)cmd));
            }
            int idx = buf.readerIndex();
            buf.readerIndex(idx-commandStart);
            String hex = ChannelBufferTools.readHexString(buf, commandLength*2); 
            command.setCommand(hex);            
            command.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
            commands.add(command);
        }
        */
        return commands;
    }
    
    private DeviceCommand parseA1(Channel channel, ChannelBuffer buf){
        if(!"%%".equals(buf.toString(A1_EMAIL_SIZE+1, 2, Charset.defaultCharset()))){
            // Признак начала пакета расположен не на своем месте в позиции {0}, ожидалось в позиции 14
            return null;
        }
        byte[] destinationArray = new byte[0x90]; //144        
        buf.getBytes(0x10, destinationArray, 0, 0x90);
        
        //Level1Converter
        int num2 = L1SymbolToValue(destinationArray[2]) << 2;
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
        short num3 = L1SymbolToValue(destinationArray[7]);
        //Util
        byte num4 = CalculateLevel1CRC(destinationArray, 0, num2);
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
        
        Object description = DecodeLevel3Message(L1Decode6BitTo8(buffer4));
        String ShortID = L1BytesToString(buffer3);
        //description.Source = message;
        //description.Message = Util.GetStringFromByteArray(message);


        return null;
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

    public static short L1SymbolToValue(byte symbol) {
        byte ASCII_CODE_A = 65; //Convert.ToByte('A');        
        byte num = 0;        
        int symbolCode = symbol & 0xFF;      
        
        char ch = (char) (symbolCode & 0xFF); //Convert.ToChar(symbolCode);
        if ((ch >= 'A') && (ch <= 'Z')){
            return (byte) (symbolCode - ASCII_CODE_A);
        }
        if ((ch >= 'a') && (ch <= 'z')){
            byte ca = 97;//(byte) str.charAt('a');
            return (byte) ((symbolCode - ca) + 0x1a);
        }
        if ((ch >= '0') && (ch <= '9')){
            byte c0 = 48;//(byte) .charAt('0');
            return (byte) ((symbolCode - c0) + 0x34);
        }
        if (ch == '+'){
            return 0x3e;
        }
        if (ch == '-'){
            return 0x3f;
        }
        if ((symbolCode >= 0x3a) && (symbolCode <= 0x3f)){
            num = (byte) ((symbolCode - 0x3a) + ASCII_CODE_A);
        }
        return num;
    }

    public static String L1BytesToString(byte[] source){
        if (source == null){
            //throw new ArgumentNullException("source", "Не передан массив байт source");
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < source.length; i++){
            builder.append((char) L1SymbolToValue(source[i]));
        }
        return builder.toString();
    }

    private static byte CalculateLevel1CRC(byte[] source, int startIndex, int length){
        if ((source == null) || (source.length == 0)){
            //throw new ArgumentNullException("source", "Не передан массив байт source");
            return 0;
        }
        if ((startIndex < 0) || (startIndex >= source.length)){
            //throw new ArgumentOutOfRangeException(
            //"startIndex", "Для параметра startIndex должны выполняться условия: (startIndex >= 0) && (startIndex < source.Length)");
            return 0;
        }
        int num = startIndex + length;
        if ((length <= 0) || (num >= source.length)){
            //throw new ArgumentOutOfRangeException(
            //"length", "Для параметра length должны выполняться условия: (length > 0) && (startIndex + length < source.Length)");
            return 0;
        }
        int num2 = 0;
        for (int i = startIndex; i <= num; i++){
            if (i != 7){
                num2 += source[i];
                num2 &= 0xff;
            }
        }
        num2 = num2 >> 2;
        num2 &= 0xff;
        return (byte) num2;
    }

    private static byte[] L1Decode6BitTo8(byte[] dataBlock){
        if (dataBlock == null){
            //throw new ArgumentNullException("dataBlock", "Не передан массив байт dataBlock");
            return null;
        }
        if (dataBlock.length != 0x88){
            //throw new ArgumentException("dataBlock", string.Concat(new object[] { "Длина переданного массива dataBlock (", dataBlock.Length, ") не соответсвует требуемой длине - ", (byte) 0x88 }));
            return null;
        }
        byte[] buffer = new byte[0x66];
        int num = 0x22;
        for (int i = 0; i < num; i++){
            int index = 4 * i;
            short num6 = L1SymbolToValue(dataBlock[index]);
            short num7 = L1SymbolToValue(dataBlock[index + 1]);
            short num8 = L1SymbolToValue(dataBlock[index + 2]);
            short num9 = L1SymbolToValue(dataBlock[index + 3]);
            short num3 = (short) ((num6 << 2) & 0xFF);
            short num4 = (short) ((num7 << 2) & 0xFF);
            short num5 = (short) ((num8 << 2) & 0xFF);
            short num10 = (short) ((num9 << 6) & 0xFF);
            num10 = (short) ((num10 >> 6) & 0xFF);
            short num11 = (short) ((num9 << 4) & 0xFF);
            num11 = (short) ((num11 >> 6) & 0xFF);
            short num12 = (short) ((num9 << 2) & 0xFF);
            num12 = (short) ((num12 >> 6) & 0xFF);
            num3 = (short) (num3 + num10);
            num4 = (short) (num4 + num11);
            num5 = (short) (num5 + num12);
            buffer[i * 3] = (byte) (num3 & 0xff);
            buffer[(i * 3) + 1] = (byte) (num4 & 0xff);
            buffer[(i * 3) + 2] = (byte) (num5 & 0xff);
        }
        return buffer;
    }
    
    /*
    * Обработка сообщения на 3-ем уровне LEVEL3 
    * Parameters command byte[102] Пакет команды LEVEL3
    */
    private Object DecodeLevel3Message(byte[] command){
        Object result = null;
        if (command.length != 0x66){
            //throw new A1Exception(string.Format(
            //"A1_1. Длина пакета на уровне {0} не соответствует требованиям протокола. Ожидалось {1} байт, принято - {2}.", "LEVEL3", (byte) 0x66, command.Length));
        }
        byte commandID = command[0];
        //Level4Converter.
        int messageID = L4ToInt16(command, 1); //BytesToUShort(command, 1);
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
    
    //public static ushort BytesToUShort(byte[] source, int startIndex){
        //return (ushort) ToInt16(source, startIndex);
    //}
    /*
    * Level4 BytesToUShort Преобразование массива байт в short - ushort. Значение представлено как int32.
    * Parameters
    * source byte[] длина больше или равна 1
    * startIndex Индекс начиная с которого будет производиться преобразование
    * Return Value Int
    */
    private int L4ToInt16(byte[] source, int startIndex){
        if (source == null){
            //throw new ArgumentNullException("source", "Не передан массив байт source");
        }
        if (source.length == 0){
            //throw new ArgumentException("source", "Передан пустой массив байт source");
        }
        if ((startIndex < 0) || (startIndex >= source.length)){
            //throw new ArgumentOutOfRangeException("startIndex", "startIndex должен быть больше или равен нулю и меньше длины массива source");
        }
        int num = 0;
        int num2 = 0;
        if ((startIndex + 1) < source.length){
            num = source[startIndex] & 0xFF;
            num2 = source[startIndex + 1] & 0xFF;
        }
        else {
            num2 = source[startIndex] & 0xFF;
        }
        return ((num << 8) | num2);
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
        extendedInfo.set("CourseBend", L4ToInt16(command, 1));
        extendedInfo.set("Distance1", L4ToInt16(command, 3));
        extendedInfo.set("Distance2", L4ToInt16(command, 5));
        
        
        for (int i = 0; i < 0x20; i++){
            extendedInfo.set("EventMask"+i, L4ToInt16(command, (i << 1) + 7));
        }
        
        extendedInfo.set("MinSpeed", L4ToInt16(command, 0x47));
        extendedInfo.set("Timer1", L4ToInt16(command, 0x49));
        extendedInfo.set("Timer2", L4ToInt16(command, 0x4b));
        
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
        extendedInfo.set("DevIdShort", L4BytesToString(command,0, 4));
        //DevIdLong = Level4Converter.BytesToString(command, 4, 0x10), 
        extendedInfo.set("DevIdLong", L4BytesToString(command,4, 0x10));
        //ModuleIdGps = Level4Converter.BytesToString(command, 20, 4), 
        extendedInfo.set("ModuleIdGps", L4BytesToString(command, 20, 4));
        //ModuleIdGsm = Level4Converter.BytesToString(command, 0x18, 4), 
        extendedInfo.set("ModuleIdGsm", L4BytesToString(command, 0x18, 4));
        //ModuleIdRf = Level4Converter.BytesToString(command, 0x20, 4), 
        extendedInfo.set("ModuleIdRf", L4BytesToString(command, 0x20, 4));
        //ModuleIdSs = Level4Converter.BytesToString(command, 0x24, 4), 
        extendedInfo.set("ModuleIdSs", L4BytesToString(command, 0x24, 4));
        //VerProtocolLong = Level4Converter.BytesToString(command, 40, 0x10), 
        extendedInfo.set("VerProtocolLong", L4BytesToString(command, 40, 0x10));
        //VerProtocolShort = Level4Converter.BytesToString(command, 0x38, 2) };
        extendedInfo.set("VerProtocolShort", L4BytesToString(command, 0x38, 2));
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config;
    }

    private String L4BytesToString(byte[] source, int startIndex, int length){
        if (source == null)    {
            return ""; //throw new ArgumentNullException("source", "Не передан массив байт source");
        }
        if ((startIndex < 0) || (startIndex >= source.length)){
            return ""; //throw new ArgumentOutOfRangeException("startIndex", "Для параметра startIndex должны выполняться условия: (startIndex >= 0) && (startIndex < source.Length)");
        }
        int num = startIndex + length;
        if ((length <= 0) || (num >= source.length)){
            return "";
            //throw new ArgumentOutOfRangeException("length", "Для параметра length должны выполняться условия: (length > 0) && (startIndex + length < source.Length)");
        }
        StringBuilder builder = new StringBuilder();
        for (int i = startIndex; i < num; i++){
            char ch = ConvertAsciiWin1251ToChar(source[i]);
            if (((byte) ch) == 0){
                break;
            }
            builder.append(ch);
        }
        return builder.toString();
    }
    
    private String L4BytesToTelNumber(byte[] source, int startIndex, int length){
        if (source == null){
            //throw new ArgumentNullException("source", "Не передан массив байт source");
        }
        if ((startIndex < 0) || (startIndex >= source.length)){
            //throw new ArgumentOutOfRangeException("startIndex", "Для параметра startIndex должны выполняться условия: (startIndex >= 0) && (startIndex < source.Length)");
        }
        int num = startIndex + length;
        if ((length <= 0) || (num >= source.length)){
            //throw new ArgumentOutOfRangeException("length", "Для параметра length должны выполняться условия: (length > 0) && (startIndex + length < source.Length)");
        }
        String str = "";
        for (int i = startIndex << 1; i < (num << 1); i++)
        {
            byte phoneNumberSymbol = L4GetPhoneNumberSymbol(source, i);
            if (phoneNumberSymbol >= 13){
                return str;
            }
            switch (phoneNumberSymbol){
                case 10:
                    str = str + "+";
                    break;

                case 11:
                    str = str + "*";
                    break;

                case 12:
                    str = str + "#";
                    break;

                default:
                    str = str + phoneNumberSymbol;//.toString();
                    break;
            }
        }
        return str;
    }

    private byte L4GetPhoneNumberSymbol(byte[] source, int index){
        if ((source == null) || (source.length == 0)) {
            //throw new ArgumentNullException("source", "Не передан массив байт source");
        }
        if ((index < 0) || (index >= (source.length << 1))) {
            //throw new ArgumentOutOfRangeException("index", "Для параметра index должны выполняться условия: (index >= 0) && (index < source.Length)");
        }
        if ((index & 1) != 0){
            return (byte) (source[index >> 1] & 15);
        }
        return (byte) (source[index >> 1] >> 4);
    }

    private int L4BytesToInt(byte[] source, int startIndex){
        //return (int) ToInt32(source, startIndex);
        if (source == null){
            //throw new ArgumentNullException("source", "Не передан массив байт source");
        }
        if (source.length == 0){
            //throw new ArgumentException("source", "Передан пустой массив байт source");
        }
        if ((startIndex < 0) || (startIndex >= source.length)) {
            //throw new ArgumentOutOfRangeException("startIndex", "startIndex должен быть больше или равен нулю и меньше длины массива source");
        }
        int num = 0;
        int num2 = 0;
        if ((startIndex + 2) < source.length){
            num = L4ToInt16(source, startIndex);
            num2 = L4ToInt16(source, startIndex + 2);
        }
        else {
            num2 = source[startIndex];
        }
        ///(long) 
        return ((num << 0x10) | (num2 & 0xffff));

    }




    public char ConvertAsciiWin1251ToChar(byte code){
        if (code < 0xc0){
            switch ((int)code){
                case 0xa8:
                    return 'Ё';

                case 0xb8:
                    return 'ё';
            }
            return (char)code; //Convert.ToChar(code);
        }
        return /*Convert.ToChar*/(char)((int) ((code + 0x410) - 0xc0));
    }

    
    private DeviceCommand GetGprsBaseConfig(byte[] command, byte cmd){
        ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
        DeviceCommand config = new DeviceCommand();
        config.setDeviceId(deviceId);
        config.setImei(deviceImei);
        
        extendedInfo.set("command", cmd);
        
        //return new GprsBaseConfig { 
        //    Mode = Level4Converter.BytesToUShort(command, 0), 
        extendedInfo.set("Mode", L4ToInt16(command, 0));
        //    ApnServer = Level4Converter.BytesToString(command, 2, 0x19), 
        extendedInfo.set("ApnServer", L4BytesToString(command, 2, 0x19));
        //    ApnLogin = Level4Converter.BytesToString(command, 0x1b, 10), 
        extendedInfo.set("ApnLogin", L4BytesToString(command, 0x1b, 10));
        //    ApnPassword = Level4Converter.BytesToString(command, 0x25, 10), 
        extendedInfo.set("ApnPassword", L4BytesToString(command, 0x25, 1));
        //    DnsServer = Level4Converter.BytesToString(command, 0x2f, 0x10),
        extendedInfo.set("DnsServer", L4BytesToString(command, 0x2f, 0x10));
        //    DialNumber = Level4Converter.BytesToString(command, 0x3f, 11), 
        extendedInfo.set("DialNumber", L4BytesToString(command, 0x3f, 11));
        //    GprsLogin = Level4Converter.BytesToString(command, 0x4a, 10), 
        extendedInfo.set("GprsLogin", L4BytesToString(command, 0x4a, 10));
        //    GprsPassword = Level4Converter.BytesToString(command, 0x54, 10) 
        extendedInfo.set("GprsPassword", L4BytesToString(command, 0x54, 10));
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
        extendedInfo.set("SmtpServer", L4BytesToString(command, 0, 0x19));
        //SmtpLogin = Level4Converter.BytesToString(command, 0x19, 10), 
        extendedInfo.set("SmtpLogin", L4BytesToString(command, 0x19, 10));
        //SmtpPassword = Level4Converter.BytesToString(command, 0x23, 10), 
        extendedInfo.set("SmtpPassword", L4BytesToString(command, 0x23, 10));
        //Pop3Server = Level4Converter.BytesToString(command, 0x2d, 0x19), 
        extendedInfo.set("Pop3Server", L4BytesToString(command, 0x2d, 0x19));
        //Pop3Login = Level4Converter.BytesToString(command, 70, 10), 
        extendedInfo.set("Pop3Login", L4BytesToString(command, 70, 10));
        //Pop3Password = Level4Converter.BytesToString(command, 80, 10) };
        extendedInfo.set("Pop3Password", L4BytesToString(command, 80, 10));
        
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
        extendedInfo.set("Server", L4BytesToString(command, 0, 0x19));
        //Login = Level4Converter.BytesToString(command, 0x19, 10), 
        extendedInfo.set("Login", L4BytesToString(command, 0x19, 10));
        //Password = Level4Converter.BytesToString(command, 0x23, 10), 
        extendedInfo.set("Password", L4BytesToString(command, 0x23, 10));
        //ConfigPath = Level4Converter.BytesToString(command, 0x2d, 20), 
        extendedInfo.set("ConfigPath", L4BytesToString(command, 0x2d, 20));
        //PutPath = Level4Converter.BytesToString(command, 0x41, 20) };
        extendedInfo.set("PutPath", L4BytesToString(command, 0x41, 20));
        
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
        extendedInfo.set("InitString", L4BytesToString(command, 0, 50));
        //Domain = Level4Converter.BytesToString(command, 50, 0x19) };
        extendedInfo.set("Domain", L4BytesToString(command, 50, 0x19));
        
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
        extendedInfo.set("Server", L4BytesToString(command, 0, 20));
        //Port = Level4Converter.BytesToUShort(command, 20) };
        extendedInfo.set("Port", L4ToInt16(command, 20));
        
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
        extendedInfo.set("NumberAccept1", L4BytesToTelNumber(command, 0, 11));
        //NumberAccept2 = Level4Converter.BytesToTelNumber(command, 11, 11), 
        extendedInfo.set("NumberAccept2", L4BytesToTelNumber(command, 11, 11));
        //NumberAccept3 = Level4Converter.BytesToTelNumber(command, 0x16, 11), 
        extendedInfo.set("NumberAccept3", L4BytesToTelNumber(command, 0x16, 11));
        //NumberDspt = Level4Converter.BytesToTelNumber(command, 0x21, 11), 
        extendedInfo.set("NumberDspt", L4BytesToTelNumber(command, 0x21, 11));
        //Name1 = Level4Converter.BytesToString(command, 0x2d, 8), 
        extendedInfo.set("Name1", L4BytesToString(command, 0x2d, 8));
        //Name2 = Level4Converter.BytesToString(command, 0x35, 8), 
        extendedInfo.set("Name2", L4BytesToString(command, 0x35, 8));
        //Name3 = Level4Converter.BytesToString(command, 0x3d, 8), 
        extendedInfo.set("Name3", L4BytesToString(command, 0x3d, 8));
        //NumberSOS = Level4Converter.BytesToTelNumber(command, 0x45, 11) };
        extendedInfo.set("NumberSOS", L4BytesToTelNumber(command, 0x45, 11));
            
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
        extendedInfo.set("DsptEmailGprs", L4BytesToString(command, 0, 30));
        //DsptEmailSMS = Level4Converter.BytesToString(command, 30, 14), 
        extendedInfo.set("DsptEmailSMS", L4BytesToTelNumber(command, 30, 14));
        //SmsCentre = Level4Converter.BytesToTelNumber(command, 0x2c, 11), 
        extendedInfo.set("SmsCentre", L4BytesToTelNumber(command, 0x2c, 11));
        //SmsDspt = Level4Converter.BytesToTelNumber(command, 0x37, 11), 
        extendedInfo.set("SmsDspt", L4BytesToTelNumber(command, 0x37, 11));
        //SmsEmailGate = Level4Converter.BytesToTelNumber(command, 0x42, 11) };
        extendedInfo.set("SmsEmailGate", L4BytesToTelNumber(command, 0x42, 11));
            
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
        extendedInfo.set("DispatcherID", L4BytesToString(command, 0, 4));
        //Password = Level4Converter.BytesToString(command, 4, 8), 
        extendedInfo.set("Password", L4BytesToString(command, 4, 8));
        //TmpPassword = Level4Converter.BytesToString(command, 12, 8) };
        extendedInfo.set("TmpPassword", L4BytesToString(command, 12, 8));
        
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
        extendedInfo.set("ZoneMsgID", L4BytesToInt(command, 0));
        //ZoneState = command[4], 
        extendedInfo.set("ZoneState", command[4]);
        //Result = command[5] };
        extendedInfo.set("Result", command[5]);
        
        config.setData(extendedInfo.getStyle(getDataManager().getStyleInfo()));
        return config; 
    }
        

    
    private List<Position> GetDataGps(byte[] command, byte cmd) {
            //DataGpsAnswer answer = new DataGpsAnswer();
        List<Position> positions = new List<Position>();
        short startIndex = 0;
        int WhatWrite = L4ToInt16(command, startIndex);
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
            if (Util.IsBitSetInMask(answer.WhatWrite, 0)){
                data.Time = Level4Converter.BytesToInt(command, startIndex);
                startIndex = (short) (startIndex + 4);
            }
            if (Util.IsBitSetInMask(answer.WhatWrite, 1)){
                data.Latitude = Level4Converter.BytesToInt(command, startIndex) * 10;
                startIndex = (short) (startIndex + 4);
            }
            if (Util.IsBitSetInMask(answer.WhatWrite, 2)){
                data.Longitude = Level4Converter.BytesToInt(command, startIndex) * 10;
                startIndex = (short) (startIndex + 4);
            }
            if (Util.IsBitSetInMask(answer.WhatWrite, 3)){
                data.Altitude = command[startIndex];
                startIndex = (short) (startIndex + 1);
            }
            if (Util.IsBitSetInMask(answer.WhatWrite, 4)){
                position.setCourse(command[startIndex]);
                startIndex = (short) (startIndex + 1);
            }
            if (Util.IsBitSetInMask(answer.WhatWrite, 5)){
                data.Speed = command[startIndex];
                startIndex = (short) (startIndex + 1);
            }
            if (Util.IsBitSetInMask(answer.WhatWrite, 6)){
                data.LogID = Level4Converter.BytesToInt(command, startIndex);
                startIndex = (short) (startIndex + 4);
            }
            if (Util.IsBitSetInMask(answer.WhatWrite, 7)){
                data.Flags = command[startIndex];
                startIndex = (short) (startIndex + 1);
            }
            if (Util.IsBitSetInMask(answer.WhatWrite, 8)){
                data.Events = Level4Converter.BytesToUInt(command, startIndex);
                startIndex = (short) (startIndex + 4);
            }
            if (Util.IsBitSetInMask(answer.WhatWrite, 9)){
                data.Sensor1 = command[startIndex];
                startIndex = (short) (startIndex + 1);
                data.Sensor2 = command[startIndex];
                startIndex = (short) (startIndex + 1);
                data.Sensor3 = command[startIndex];
                startIndex = (short) (startIndex + 1);
                data.Sensor4 = command[startIndex];
                startIndex = (short) (startIndex + 1);
                data.Sensor5 = command[startIndex];
                startIndex = (short) (startIndex + 1);
                data.Sensor6 = command[startIndex];
                startIndex = (short) (startIndex + 1);
                data.Sensor7 = command[startIndex];
                startIndex = (short) (startIndex + 1);
                data.Sensor8 = command[startIndex];
                startIndex = (short) (startIndex + 1);
            }
            if (Util.IsBitSetInMask(answer.WhatWrite, 10)){
                data.Counter1 = Level4Converter.BytesToUShort(command, startIndex);
                startIndex = (short) (startIndex + 2);
                data.Counter2 = Level4Converter.BytesToUShort(command, startIndex);
                startIndex = (short) (startIndex + 2);
                data.Counter3 = Level4Converter.BytesToUShort(command, startIndex);
                startIndex = (short) (startIndex + 2);
                data.Counter4 = Level4Converter.BytesToUShort(command, startIndex);
                startIndex = (short) (startIndex + 2);
            }
            if ((Math.Abs(data.Longitude) > 0x66ff300) || (Math.Abs(data.Latitude) > 0x337f980)){
                data.Valid = false;
            }
            else{
                data.Valid = (data.Flags & 8) != 0;
            }
            positions.add(position);
        }
        return positions;
    }
        
}
