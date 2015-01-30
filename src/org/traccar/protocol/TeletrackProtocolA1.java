/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.traccar.protocol;

/**
 *
 * @author dima
 */
public class TeletrackProtocolA1 {
    public static boolean IsBitSetInMask(int mask, byte bitIndex){
        int num = (((int) 1) << bitIndex);
        if ((num & mask) == 0){
            return false;
        }
        return true;
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

    public static byte CalculateLevel1CRC(byte[] source, int startIndex, int length){
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
                short src = (short)(source[i] & 0xFF);
                num2 += (src & 0xFF);
                num2 &= 0xff;
            }
        }
        num2 = num2 >> 2;
        num2 &= 0xff;
        return (byte) num2;
    }

    public static byte[] L1Decode6BitTo8(byte[] dataBlock){
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
    public static int L4ToInt16(byte[] source, int startIndex){
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

    
    public static String L4BytesToString(byte[] source, int startIndex, int length){
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
        builder.append("");
        for (int i = startIndex; i < num; i++){
            char ch = ConvertAsciiWin1251ToChar( (short)(source[i] & 0xFF) );
            if (((byte) ch) == 0){
                break;
            }
            builder.append(ch);
        }
        return builder.toString();
    }
    
    public static String L4BytesToTelNumber(byte[] source, int startIndex, int length){
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
            short phoneNumberSymbol = L4GetPhoneNumberSymbol(source, i);
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

    public static short L4GetPhoneNumberSymbol(byte[] source, int index){
        if ((source == null) || (source.length == 0)) {
            //throw new ArgumentNullException("source", "Не передан массив байт source");
        }
        if ((index < 0) || (index >= (source.length << 1))) {
            //throw new ArgumentOutOfRangeException("index", "Для параметра index должны выполняться условия: (index >= 0) && (index < source.Length)");
        }
        if ((index & 1) != 0){
            return (short) (source[index >> 1] & 15);
        }
        return (short) ((source[index >> 1] & 0xFF) >> 4);
    }

    public static int L4BytesToInt(byte[] source, int startIndex){
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

    public static char ConvertAsciiWin1251ToChar(short code){
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
    public static byte ConvertCharToAsciiWin1251(char symbol){
        if (symbol == 'Ё'){
            symbol = 'Е';
        }
        else if (symbol == 'ё'){
            symbol = 'е';
        }
        if (symbol < 'Ā'){
            return (byte) (symbol & 0xFF); //Convert.ToByte(symbol);
        }
        if ((symbol >= 'А') && (symbol <= 'я'))
        {
            return (byte) (symbol & 0xFF); //(byte) (('\x00c0' + symbol) - 0x410);
        }
        return 63; //Convert.ToByte('?');
    }

    //Encoder Levels    
    public static byte[] L4UIntToBytes(long value){
        Long val = ((value >> 0x10) & 0xFFFF);        
        byte[] buffer = L4UShortToBytes( Integer.valueOf(val.toString()) );
        val = (value & 0xFFFF);
        byte[] buffer2 = L4UShortToBytes( Integer.valueOf(val.toString()) );
        return new byte[] { buffer[0], buffer[1], buffer2[0], buffer2[1] };
    }
    public static byte[] L4UShortToBytes(int value){
        byte[] result = new byte[2];
        result[0] = (byte)((value >> 8) & 0xFF);
        result[1] = (byte) (value & 0xFF);
        return result;
    }
    public static byte[] L4IntToBytes(int value){
        byte[] buffer = L4UShortToBytes((int) (value >> 0x10));
        byte[] buffer2 = L4UShortToBytes((int) value);
        return new byte[] { buffer[0], buffer[1], buffer2[0], buffer2[1] };
    }
    public static byte[] L4StringToBytes(String value, int maxLength){
        if (value == null){
            //throw new ArgumentNullException("value", "Не передана строка value");
            return null;
        }
        if (maxLength <= 0){
            //throw new ArgumentOutOfRangeException("maxLength", "Параметр maxLength должен быть больше нуля");
            return null;
        }
        byte[] buffer = new byte[value.length()];
        for (int i = 0; i < Math.min(value.length(), maxLength); i++){
            buffer[i] = ConvertCharToAsciiWin1251(value.charAt(i));
        }
        return buffer;
    }
    public static byte[] L4TelNumberToBytes(String phoneNumber, int maxLength){
        if (phoneNumber == null){
            //throw new ArgumentNullException("phoneNumber", "Не передан телефонный номер");
            return null;
        }
        if (maxLength <= 0){
            //throw new ArgumentOutOfRangeException("maxLength", "maxLength должен быть больше нуля");
            return null;
        }
        byte[] result = new byte[maxLength];
        int index = 0;
        int num2 = (maxLength << 1) - 2;
        while ((index < phoneNumber.length()) && (index < num2)) {
            L4WriteTelNumberSymbol(phoneNumber.charAt(index), result, index);
            index++;
        }
        L4WriteTelNumberSymbol(';', result, index);
        index++;
        L4WriteTelNumberSymbol('\0', result, index);
        return result;
    }
    private static void L4WriteTelNumberSymbol(char symbol, byte[] result, int index){
        short num = 0xff;
        switch (symbol){
            case '*':
                num = 11;
                break;
            case '+':
                num = 10;
                break;
            case ';':
                num = 13;
                break;
            case '\0':
                num = 15;
                break;
            case '#':
                num = 12;
                break;
            default:
                if (((symbol & 0xFF) >= 0x30) && ((symbol & 0xFF) <= 0x39)){
                    num = (short)((symbol & 0xFF) - 0x30);
                }
                break;
        }
        if (num < 0x10)
        {
            if ((index & 1) != 0) {
                result[index >> 1] = (byte) ((result[index >> 1] & 240) | (num & 15));
            }
            else {
                result[index >> 1] = (byte) ((result[index >> 1] & 15) | ((num & 15) << 4));
            }
        }
    }
    
    public static byte L1ValueToSymbol(byte value){
        byte ASCII_CODE_A = 65; //Convert.ToByte('A');  
        
        byte num = (byte) ('.' & 0xFF); //Convert.ToByte('.');
        if (value <= 0x19){
            return (byte) (ASCII_CODE_A + value);
        }
        if ((value >= 0x1a) && (value <= 0x33)){
            //Convert.ToByte('a') 0x61
            return (byte) ((0x61 + value) - 0x1a);
        }
        if ((value >= 0x34) && (value <= 0x3d)){
            //Convert.ToByte('0') 0x30
            return (byte) ((0x30 + value) - 0x34);
        }
        if (value == 0x3e){
            return 0x2B; //Convert.ToByte('+');
        }
        if (value == 0x3f){
            return 0x2D; //Convert.ToByte('-');
        }
        //Convert.ToByte('F') 0x46
        if ((value >= ASCII_CODE_A) && (value <= 0x46)){
            num = (byte) ((value - ASCII_CODE_A) + 0x3a);
        }
        return num;
    }
    
    
    public static byte[] L1Encode8BitTo6(byte[] source){
        if (source == null){
            //throw new ArgumentNullException("source", "Не передан массив байт source");
            return null;
        }
        if (source.length != 0x66){
            //throw new ArgumentException("source", string.Concat(new object[] { "Длина переданного массива source (", source.Length, ") не соответсвует требуемой длине - ", (byte) 0x66 }));
            return null;
        }
        byte[] buffer = new byte[0x88];
        for (int i = 0; i < 0x22; i++){
            short num5 = 0;
            short num6 = 0;
            short num7 = 0;
            short num8 = 0;
            int index = 3 * i;
            short num2 = (short) (source[index] & 0xFF);
            short num3 = (short) (source[index + 1] & 0xFF);
            short num4 = (short) (source[index + 2] & 0xFF);
            num5 = (short) ((num2 >> 2) & 0xFF);
            num6 = (short) ((num3 >> 2) & 0xFF);
            num7 = (short) ((num4 >> 2) & 0xFF);
            num2 = (short) ((num2 << 6) & 0xFF);
            num2 = (short) ((num2 >> 6) & 0xFF);
            num3 = (short) ((num3 << 6) & 0xFF);
            num3 = (short) ((num3 >> 4) & 0xFF);
            num4 = (short) ((num4 << 6) & 0xFF);
            num4 = (short) ((num4 >> 2) & 0xFF);
            num8 = (short) (((num2 + num3) + num4) & 0xFF);
            buffer[i * 4] = L1ValueToSymbol((byte)(num5 & 0xFF));
            buffer[(i * 4) + 1] = L1ValueToSymbol((byte)(num6 & 0xFF));
            buffer[(i * 4) + 2] = L1ValueToSymbol((byte)(num7 & 0xFF));
            buffer[(i * 4) + 3] = L1ValueToSymbol((byte)(num8 & 0xFF));
        }
        return buffer;
    }

    //Encoder
    
    
    public static byte[] GetCommandLevel3Template(byte commandId, int messageId){
        byte[] buffer = new byte[0x66];
        buffer[0] = commandId;
        byte[] buffer2 = L4UShortToBytes(messageId);
        buffer[1] = buffer2[0];
        buffer[2] = buffer2[1];
        for (int i = 3; i < 0x66; i++){
            buffer[i] = 0x5f;
        }
        return buffer;
    }

    public static byte[] GetMessageLevel0(byte[] level1Message, String email){
        if (level1Message == null){
            //throw new ArgumentNullException("level1Message", "Не передан массив байт level1Message");
            return null;
        }
        if (level1Message.length != 0x90) {
            //throw new A1Exception(string.Format("A1_1. Длина пакета на уровне {0} не соответствует требованиям протокола. Ожидалось {1} байт, принято - {2}.", "LEVEL3", (byte) 0x90, level1Message.Length));
            return null;
        }
        if (email == null){
            //throw new ArgumentNullException("email", "Не передан параметр email");
            return null;
        }
        if (email.length() > 13){
            //throw new A1Exception(string.Format("A1_8. Длина Email не может быть больше {0} символов.", (byte) 13));
            return null;
        }
        byte[] destinationArray = new byte[160];
        //byte[] emailArr = email.getBytes();
        for (int i = 0; i <= 13; i++){
            if (i < email.length()){
                short num2 = (short) (email.charAt(i) & 0xFF); //Convert.ToByte(email[i]);
                switch (num2)
                {
                    case 0:
                    case 0xff:
                    {
                        destinationArray[i] = 32;//Convert.ToByte(' ');
                        continue;
                    }
                }
                destinationArray[i] = (byte)num2;
            }
            else
            {
                destinationArray[i] = 32;//Convert.ToByte(' ');
            }
        }
        destinationArray[13] = 32;//Convert.ToByte(' ');
        destinationArray[14] = 0x25;
        destinationArray[15] = 0x25;
        //Array.Copy(level1Message, 0, destinationArray, 0x10, 0x90);
        System.arraycopy(level1Message, 0, destinationArray, 0x10, 0x90);
        return destinationArray;
    }

    public static byte[] GetMessageLevel1(byte[] level3Command, String devShortId){
        if (level3Command == null){
            //throw new ArgumentNullException("level3Command", "Не передан массив байт level3Command");
            return null;
        }
        if (level3Command.length != 0x66){
            //throw new A1Exception(string.Format("A1_1. Длина пакета на уровне {0} не соответствует требованиям протокола. Ожидалось {1} байт, принято - {2}.", "LEVEL3", (byte) 0x66, level3Command.Length));
            return null;
        }
        //if (String.IsNullOrEmpty(devShortId)){
            //throw new ArgumentNullException("devShortId", "Не передан параметр devShortId");
        //    return null;
        //}
        byte[] destinationArray = new byte[0x90];
        destinationArray[0] = L1ValueToSymbol((byte)0);
        destinationArray[1] = L1ValueToSymbol((byte)0);
        destinationArray[2] = L1ValueToSymbol((byte)0x22);
        destinationArray[3] = L1ValueToSymbol(((byte)devShortId.charAt(0)));
        destinationArray[4] = L1ValueToSymbol((byte)(devShortId.charAt(1)));
        destinationArray[5] = L1ValueToSymbol((byte)(devShortId.charAt(2)));
        destinationArray[6] = L1ValueToSymbol((byte)(devShortId.charAt(3)));
        //Array.Copy(Level1Converter.Encode8BitTo6(level3Command), 0, destinationArray, 8, 0x88);
        byte[] bit6 = L1Encode8BitTo6(level3Command);
        System.arraycopy(bit6, 0, destinationArray, 8, 0x88);
        
        byte crc = CalculateLevel1CRC(destinationArray, 0, 0x88);
        destinationArray[7] = L1ValueToSymbol(crc);
        return destinationArray;
    }
    
    public static String GetStringFromByteArray(byte[] source){
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < source.length; i++){
            builder.append((char) (source[i] & 0xFF));
        }
        return builder.toString();
    }
    
    public static void FillCommandAttribute(byte[] sourceAttribute, byte[] destinationCommand, int startIndex, int alignLength){
        if (sourceAttribute == null){
            //throw new ArgumentNullException("sourceAttribute", "Не передан массив байт sourceAttribute");
        }
        if ((destinationCommand == null) && (destinationCommand.length == 0)){
            //throw new ArgumentNullException("destinationCommand", "Не передан массив байт destinationCommand");
        }
        if ((startIndex < 0) || (startIndex >= destinationCommand.length)){
            //throw new ArgumentOutOfRangeException("startIndex", "Для параметра startIndex должны выполняться условия: (startIndex >= 0) && (startIndex < destinationCommand.Length)");
        }
        if ((alignLength <= 0) || ((startIndex + alignLength) >= destinationCommand.length)){
            //throw new ArgumentOutOfRangeException("alignLength", "Для параметра alignLength должны выполняться условия: (alignLength > 0) && (startIndex + alignLength < destinationCommand.Length)");
        }
        byte[] destinationArray = new byte[alignLength];
        //Array.Copy(sourceAttribute, 0, destinationArray, 0, Math.Min(alignLength, sourceAttribute.length));
        System.arraycopy(sourceAttribute, 0, destinationArray, 0, Math.min(alignLength, sourceAttribute.length));
        //Array.Copy(destinationArray, 0, destinationCommand, startIndex, alignLength);
        System.arraycopy(destinationArray, 0, destinationCommand, startIndex, alignLength);
    }

    public static byte GetMaskForZone(byte entryFlag, byte exitFlag, byte inFlag, byte OutFlag){
        short num = 0;
        if (entryFlag != 0){
            num = 1;
        }
        if (exitFlag == 0){
            num = (short) (num & 0xfd);
        }
        else{
            num = (short) (num | 2);
        }
        if (inFlag == 0){
            num = (short) (num & 0xfb);
        }
        else{
            num = (short) (num | 4);
        }
        if (OutFlag == 0){
            return (byte) (num & 0xf7);
        }
        return (byte) (num | 8);
    }
}
