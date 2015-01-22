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
    
    private ChannelBuffer getAck(boolean error){
        ChannelBuffer response = ChannelBuffers.directBuffer(1);
        //response.writeByte(0x30); //'0'
        
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

    private void parseIdentification(Channel channel, ChannelBuffer buf) {
        boolean result = false;
        
        String devID = buf.toString(4, 4, Charset.defaultCharset());
        int pwdLen = 0;
        
        //Проверка пароля ???
        /*
        for (int i=9; i < buf.writerIndex(); i++){
            if(buf.getByte(i) == 0){
                pwdLen = i - 9;
                break;
            }
        }        
        String DevPwd = buf.toString(9, pwdLen ,Charset.defaultCharset());
        */
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
            //ChannelBuffer response = ChannelBuffers.directBuffer(1);
            //response.writeByte(result ? 1 : 0);
            //response.writeByte(0x30); //'0'
            //channel.write(response);
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
            }
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
    
    private List<Position> parseLocation(Channel channel, ChannelBuffer buf) {
        List<Position> positions = new LinkedList<Position>();
        
        buf.skipBytes(ATR_LENGTH); // marker
        short count = buf.readUnsignedByte();
        
        for (int i = 0; i < count; i++) {
            Position position = new Position();
            ExtendedInfoFormatter extendedInfo = new ExtendedInfoFormatter(getProtocol());
            
            position.setDeviceId(deviceId);
            position.setTableName(tableName);
            position.setImei(deviceImei);
            
            long logId = readUInt(buf); //buf.readUnsignedInt(); //4b
            extendedInfo.set("logId", logId);
            
            long time = readUInt(buf); //buf.readUnsignedInt();  //4b
            //time += 1167609600; // 2007-01-01 00:00:00
            //position.setTime(new Date(time * 1000));
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
            
            
            //extendedInfo.set("io" + buf.readUnsignedByte(), buf.readLong());
            position.setExtendedInfo(extendedInfo.getStyle(getDataManager().getStyleInfo()));
            positions.add(position);
        }
        
        if (channel != null) {
            //ChannelBuffer response = ChannelBuffers.directBuffer(1);
            //response.writeByte(48);
            //channel.write(response);
            channel.write(getAck(false));
        }
        return positions;
    }
    
    /*
    * Разбор команды от устройства
    */
    private List<DeviceCommand> parseCommand(Channel channel, ChannelBuffer buf){
        buf.skipBytes(2); //
        buf.readUnsignedShort(); // data length
        int count = buf.readUnsignedByte(); // count
        
        List<DeviceCommand> commands = new LinkedList<DeviceCommand>();        

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
        return commands;
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
        
        if("%%AU".equals(PacketID)){
            parseIdentification(channel, buf);
            
            packetClear();
        }
        else if("%%AE".equals(PacketID)){
            
            packetClear();
        }
        else if("%%CR".equals(PacketID)){
            //CMD_RE_TT = Encoding.ASCII.GetBytes("%%CR");

        }
        else if("%%MB".equals(PacketID)){
            packetClear();
            
            short count = buf.getUnsignedByte(ATR_LENGTH);
            
            if((count+ATR_LENGTH+1)==buf.readableBytes()){
                //Пакет полный     
                parseLocation(channel, buf);
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
            //PK_MBIN_TT = Encoding.ASCII.GetBytes("%%PB");
        }
        else if("%%RE".equals(PacketID)){
            //RESP_TT = Encoding.ASCII.GetBytes("%%RE");
        }
        else if("%%".equals(PacketID)){ //A1
            //public static readonly int A1_ATR_LENGTH = 3;
            //public static readonly int A1_EMAIL_SIZE = 13;
            //public static readonly int ATR_LENGTH = 4;

        }
        else if("%%MB".equals(packetId) && ((packetIndex + buf.readableBytes())<=packetLen)){
            packetIndex += buf.readableBytes();
            packetBuf.writeBytes(buf);
        }
        
        if(("%%MB".equals(packetId)) && (packetIndex == packetLen)){
            return parseLocation(channel, packetBuf);
        }
        
        //if ((buf.getUnsignedShort(0)==0)&&(buf.getUnsignedShort(2)>0)){
        //    Log.debug("config");
        //    return parseCommand(channel, buf);
        //    
        //} else if (buf.getUnsignedShort(0) > 0) {
        //    Log.debug("parseIdentification");
        //    parseIdentification(channel, buf);
        //}
        //else {
        //    Log.debug("parseLocation");
        //    return parseLocation(channel, buf);
        //}
        
        return null;
    }

    /*
    * Разбор значения по номеру команды
    */
    private String getReadFromIdConfig(ChannelBuffer buf, int paramId, int length){
        String paramVal = "";
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
            case 910://Пароль доступа к бутлоадеру ( по умолчанию 11111)
            
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
            case 906://Период ожидания между сериями попыток ( по умолчанию 300 сек)
            
                
            paramVal = "" + buf.readUnsignedShort();
            break;

            case 281://Угол отклонения акселерометра по оси X ( по умолчанию 3°)
            case 282://Y
            case 283://Z
            case 900://Разрешение съёма по времени ( по умолчанию 1)
            case 901://Разрешение съёма по расстоянию    
            case 902://Разрешение съёма по азимуту ( по умолчанию 1)
            case 904://Кол-во попыток в серии соединения с сервером ( по умолчанию 3)
            case 911://Разрешение сна по акселерометру ( по умолчанию 0)
                
            case 912://Кол-во гудков перед автоподъемом трубки ( по умолчанию 3)
            //case 912://Автоподъём трубки: 0 – запрещен. Число больше 5-10 таймаут составит 30-60 сек.
                
            case 915://азрешение обслуживания электронного ключа (смарт-карты). и управления выходами
//0 – обслуживание запрещено, iButton
//9 – управление выходом DOUT1,
//10 – управление выходом DOUT2, RFID
//5 – управление выходом DOUT1,
//6 – управление выходом DOUT2, ( по умолчанию 0)
            case 991://Разрешение включения электронного ключа идентификатора.
//1-включен,0-выключен ( по умолчанию 0)
            case 990://Разрешение обслуживания термодатчиков 1-включен, 0-выключен. ( по умолчанию 0)
            case 993://Датчик топлива. 0 - передаётся абсолютный расход топлива. 1 - передаётся мгновенный расход топлива. ( по умолчанию 0)
            case 992://Разрешение настройки количества спутников при потерте сигнала GPS ( по умолчанию отключен )
            case 917://Разрешение режима выбора оператора. 1- включен, 0-выключен ( по умолчанию 0)
            case 959://Период съёма=(Значение+1)*50мС Если установлено 19 то период составит (19+1)*50 = 1000 мС по умолчанию = 19
                //Периода съёма данных AIN1 медианной фильтрацией на 7 отсчётов.
            case 980://Период съёма = (Значение+1)*50мС Если установлено 19 то период составит (19+1)*50 = 1000 мС по умолчанию = 19
                //Периода съёма данных AIN2 медианной фильтрацией на 7 отсчётов.
            case 918://Передача данных gps сигнала, при минимальной скорости ( по умолчанию 5 км/ч)
            case 808://Беспроводной датчик для прицепного оборудования ( сетевой адрес 4) 1-включен 0-выключен ( по умолчанию 0)
            case 349://Фильтр для цифровых входов dlow3/dlow4 (умолчанию 5) диапазон 1-20 при 1 - 10мС, при 2 - 20мС, при 20 - 200 мС уровни длительность меньше чем заданный будут фильтроваться
            case 819://Разрешение использования значений последнего валидного уровня топлива.
//Фильтрованного и не фильтрованного датчика уровня топлива. 1-включен 0-выключен ( по умолчанию 0)  
            case 818://Введена проверка PIN-кода SIM-карты.
            case 186://Период периодической перезагрузки устройства в часах 0-255 (0-периодическая перезагрузка не выполняется)
            case 187://Тип перезагрузки, 0-полная перезагрузка устройства, 1-только модем
            case 197://Настройка периода опроса для 4-х датчиков уровня топлива RS485. (по умолчанию 100)
            case 198://Настройка периода опроса RS485 RFID (по умолчанию 100)
            case 199://Настройка периода опроса RS485 Беспроводного датчика (по умолчанию 100)  
            case 208://Настройка периода опроса RS485 iButton (по умолчанию 100)
            case 206://Настройка периода опроса RS485 Tsens (по умолчанию 100)
            case 994://Ответ на входящий звонок с помощью цифровых входов.
//1-6 - ID IO-элемента - цифрового входа, с помощью которого осуществляется ответ на входящий вызов. (0 - ответ с помощью цифрового входа запрещён)
            case 995://Настройки гарнитуры: Микрофон Значения: 0 - 7 (по молчанию 4)
            case 996://Настройки гарнитуры: Динамик Значения: 0 - 14 (по молчанию 7)
            case 950://коэффициент F для фильтра Калмана
            case 951://коэффициент Q для фильтра Калмана
            case 952://коэффициент H для фильтра Калмана
            case 953://коэффициент R для фильтра Калмана при отсутствии движения
            case 954://коэффициент R для фильтра Калмана при наличии движения
            case 209://Настройка переключения фильтров, для фильтрованных датчиков уровня топлива.
                //(0-фильтр Баттерворта)
                //(1-фильтр Калмана)
            case 188://Хост 2
            case 189://Порт 2
            case 196://Разрешение использования Host 2 Port 2
                //( 1 –включен) (0-выключен)
            paramVal = "" + buf.readUnsignedByte();
            break;
        }
        return paramVal;
    }
    
    /*
    * Формирование команды для отправки
    */
    private byte[] getBuildConfig(String cmd, int paramId, String value){
        byte[] result = null;
        byte lengthConfig = 4;
        byte lengthData = 0;
                
        //Конфигурационный пакет от сервера – запрос значения параметра
        if("getparam".equals(cmd)){
            result = new byte[3];
            result[0] = 32;
            result[1] = (byte)(paramId >> 8);
            result[2] = (byte)(paramId);
        }
        //Тип пакета – конфигурационный пакет установка значения параметра
        if("setparam".equals(cmd)){
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
                case 910://Пароль доступа к бутлоадеру ( по умолчанию 11111)
                    lengthData = (byte)(value.length() + 1);
                    result = new byte[lengthConfig + lengthData];
                    System.arraycopy(value.getBytes(), 0, result, lengthConfig, lengthData - 1);
                    result[lengthConfig + lengthData] = 0;
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
                case 906://Период ожидания между сериями попыток ( по умолчанию 300 сек)
                    lengthData = 2;
                    result = new byte[lengthConfig + lengthData];
                    int v = Integer.parseInt(value);
                    result[lengthConfig + 0] = (byte)(v >>  8);
                    result[lengthConfig + 1] = (byte)(v);
                    break;
                
                case 186://Период периодической перезагрузки устройства в часах 0-255 (0-периодическая перезагрузка не выполняется)
                case 187://Тип перезагрузки, 0-полная перезагрузка устройства, 1-только модем
                case 188://Хост 2
                case 189://Порт 2
                case 196://Разрешение использования Host 2 Port 2 ( 1 –включен) (0-выключен)
                case 197://Настройка периода опроса для 4-х датчиков уровня топлива RS485. (по умолчанию 100)
                case 198://Настройка периода опроса RS485 RFID (по умолчанию 100)
                case 199://Настройка периода опроса RS485 Беспроводного датчика (по умолчанию 100)  
                
                case 206://Настройка периода опроса RS485 Tsens (по умолчанию 100)
                case 208://Настройка периода опроса RS485 iButton (по умолчанию 100)
                case 209://Настройка переключения фильтров, для фильтрованных датчиков уровня топлива.
                    //(0-фильтр Баттерворта)
                    //(1-фильтр Калмана)
                case 281://Угол отклонения акселерометра по оси X ( по умолчанию 3°)
                case 282://Y
                case 283://Z
                case 349://Фильтр для цифровых входов dlow3/dlow4 (умолчанию 5) диапазон 1-20 при 1 - 10мС, при 2 - 20мС, при 20 - 200 мС уровни длительность меньше чем заданный будут фильтроваться
                case 808://Беспроводной датчик для прицепного оборудования ( сетевой адрес 4) 1-включен 0-выключен ( по умолчанию 0)
                case 818://Введена проверка PIN-кода SIM-карты.
                case 819://Разрешение использования значений последнего валидного уровня топлива.
    //Фильтрованного и не фильтрованного датчика уровня топлива. 1-включен 0-выключен ( по умолчанию 0)  
                case 900://Разрешение съёма по времени ( по умолчанию 1)
                case 901://Разрешение съёма по расстоянию    
                case 902://Разрешение съёма по азимуту ( по умолчанию 1)
                case 904://Кол-во попыток в серии соединения с сервером ( по умолчанию 3)
                case 911://Разрешение сна по акселерометру ( по умолчанию 0)
                case 912://Кол-во гудков перед автоподъемом трубки ( по умолчанию 3)
                //case 912://Автоподъём трубки: 0 – запрещен. Число больше 5-10 таймаут составит 30-60 сек.
                case 915://азрешение обслуживания электронного ключа (смарт-карты). и управления выходами
    //0 – обслуживание запрещено, iButton
    //9 – управление выходом DOUT1,
    //10 – управление выходом DOUT2, RFID
    //5 – управление выходом DOUT1,
    //6 – управление выходом DOUT2, ( по умолчанию 0)
                case 917://Разрешение режима выбора оператора. 1- включен, 0-выключен ( по умолчанию 0)
                case 918://Передача данных gps сигнала, при минимальной скорости ( по умолчанию 5 км/ч)
                case 950://коэффициент F для фильтра Калмана
                case 951://коэффициент Q для фильтра Калмана
                case 952://коэффициент H для фильтра Калмана
                case 953://коэффициент R для фильтра Калмана при отсутствии движения
                case 954://коэффициент R для фильтра Калмана при наличии движения
                case 959://Период съёма=(Значение+1)*50мС Если установлено 19 то период составит (19+1)*50 = 1000 мС по умолчанию = 19
                    //Периода съёма данных AIN1 медианной фильтрацией на 7 отсчётов.
                case 980://Период съёма = (Значение+1)*50мС Если установлено 19 то период составит (19+1)*50 = 1000 мС по умолчанию = 19
                    //Периода съёма данных AIN2 медианной фильтрацией на 7 отсчётов.
                case 990://Разрешение обслуживания термодатчиков 1-включен, 0-выключен. ( по умолчанию 0)
                case 991://Разрешение включения электронного ключа идентификатора.
    //1-включен,0-выключен ( по умолчанию 0)
                case 992://Разрешение настройки количества спутников при потерте сигнала GPS ( по умолчанию отключен )
                case 993://Датчик топлива. 0 - передаётся абсолютный расход топлива. 1 - передаётся мгновенный расход топлива. ( по умолчанию 0)
                case 994://Ответ на входящий звонок с помощью цифровых входов.
    //1-6 - ID IO-элемента - цифрового входа, с помощью которого осуществляется ответ на входящий вызов. (0 - ответ с помощью цифрового входа запрещён)
                case 995://Настройки гарнитуры: Микрофон Значения: 0 - 7 (по молчанию 4)
                case 996://Настройки гарнитуры: Динамик Значения: 0 - 14 (по молчанию 7)
                    lengthData = 1;                    
                    result = new byte[lengthConfig + lengthData];
                    result[lengthConfig + 0] = (byte) Integer.parseInt(value);
                    break;
            }
            
            if(result != null){
                result[0] = 36;
                result[1] = (byte)(paramId >>  8);
                result[2] = (byte)(paramId);
                result[3] = lengthData;
            }
        }
        //40 - Пакет с командой от сервера
        if ("command".equals(cmd)){
            result = new byte[2];
            result[0] = 40;
            
            if ("getgps".equals(value.toLowerCase())){
                //Возврат текущих координат GPS
                result[1] = 0;
            }
            else if ("cpureset".equals(value.toLowerCase())){
                //Выполняется сохранение системных пераметров и перезагрузка процессора
                result[1] = 1;
            }
            else if ("getver".equals(value.toLowerCase())){
                //Возвращается версия П/О треккера
                result[1] = 2;
            }
            else if ("deletegpsrecords".equals(value.toLowerCase())){
                //Стирается информация о записях GPS данных во flash-памяти
                result[1] = 4;
            }
            else if ("getio".equals(value.toLowerCase())){
                //Получение состояния цифровых входов, цифровых выходов и аналоговых входов
                result[1] = 6;
            }
            else if ("setdigout 1".equals(value.toLowerCase())){
                //Установить цифровой выход 1
                result[1] = 7;
            }
            else if ("clrdigout 1".equals(value.toLowerCase())){
                //бросить цифровой выход 1
                result[1] = 8;
            }
            else if ("setdigout 2".equals(value.toLowerCase())){
                //Установить цифровой выход 2
                result[1] = 9;
            }
            else if ("clrdigout 2".equals(value.toLowerCase())){
                //сбросить цифровой выход 2
                result[1] = 10;
            }
        }
        //43 - Командой обновления П/О
        if ("boot".equals(cmd)){
            lengthConfig = 2;
            lengthData = (byte)(value.length() + 1);
            result = new byte[lengthConfig + lengthData];
            System.arraycopy(value.getBytes(), 0, result, lengthConfig, lengthData - 1);
            result[lengthConfig + lengthData] = 0;
        }        
        return result;
    }
    
    /*
    * Имя команды по номеру
    */
    private String getCommndParam(byte cmd){
        switch (cmd) {
            case 0:
                return "getgps";
            case 1:
                return "cpureset";
            case 2:
                return "getver";
            case 4:
                return "deletegpsrecords";
            case 6:
                return "getio";
            case 7:
                return "setdigout 1";
            case 8:
                return "clrdigout 1";
            case 9:
                return "setdigout 2";
            case 10:
                return "clrdigout 2";
        }
        return "none";
    }
    /*
    * Значение команды
    */
    private String getCommandValue(ChannelBuffer buf, byte cmd, short length){
        String result="";
        switch (cmd) {
            case 0:
                /*Смещение  Размер в байтах Назначение  
0   1   Валидность данных: 0 – данные невалидны 1 – данные валидны
1   1   Кол-во спутников
2   4   Широта
6   4   Долгота
10  2   Высота
12  1   Скорость
13  2   Азимут
15  8   Время UTC
*/
                //return "getgps";
                break;
            case 1:
                //return "cpureset";
                break;
            case 2:
                result = buf.toString(buf.readerIndex(), length-1, Charset.defaultCharset());
                buf.skipBytes(length);
                //return "getver";
                break;
            case 4:
                short r = buf.readUnsignedByte();
                result = (r==255 ? "ok" : "error");
                //0 – неуспешно 255 – успешно
                //return "deletegpsrecords";
                break;
            case 6: //Получение состояния цифровых входов, цифровых выходов и аналоговых входов
                /*
Смещение    Размер в байтах Назначение
0   2   Битовая маска состояния цифровых входов:
            Бит 0 – dLow1 
            Бит 1 – dLow2
            Бит 2 – dLow3
            Бит 3 – dLow4 
            Бит 4 – dHigh1
            Бит 5 – dHigh2 
            Бит 6 – dIOpen 
            Бит 7 – dIRst
2   1   Битовая маска состояния цифровых выходов:
            Бит 0 – Dout1 
            Бит 1 – Dout2
3   2   Аналоговый вход 1, mV
5   2   Аналоговый вход 2, mV
7   2   Напряжение внешнего источника питания, mV
9   2   Напряжение батареи, mV
                */
                //return "getio";
                break;
            case 7:
                //return "setdigout 1";
                short setdigout1 = buf.readUnsignedByte();
                result = setdigout1 + "";
                break;
            case 8:
                //return "clrdigout 1";
                short clrdigout1 = buf.readUnsignedByte();
                result = clrdigout1 + "";
                break;
            case 9:
                //return "setdigout 2";
                short setdigout2 = buf.readUnsignedByte();
                result = setdigout2 + "";
                break;
            case 10:
                //return "clrdigout 2";
                short clrdigout2 = buf.readUnsignedByte();
                result = clrdigout2 + "";
                break;
        }
        return result;
    }
}
