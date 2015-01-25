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
                num2 += source[i];
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
        for (int i = startIndex; i < num; i++){
            char ch = ConvertAsciiWin1251ToChar(source[i]);
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

    public static byte L4GetPhoneNumberSymbol(byte[] source, int index){
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

    public static char ConvertAsciiWin1251ToChar(byte code){
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

    
}
