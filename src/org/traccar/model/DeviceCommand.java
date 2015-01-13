/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package org.traccar.model;

import java.util.Date;

/**
 *
 * @author dima
 */
public class DeviceCommand {
    /**
     * Id
     */
    private Long id;

    public Long getId() {
        return (id==null ? 0 : id);
    }

    public void setId(Long id) {
        this.id = id;
    }
    
    /**
     * Id device
     */
    private Long deviceId;

    public Long getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(Long deviceId) {
        this.deviceId = deviceId;
    }
    
    /**
     * Imei device
     */
    private String imei;

    public String getImei() {
        return imei;
    }

    public void setImei(String imei) {
        this.imei = imei;
    }
    
    /**
     * Command
     */
    private String command;

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }
    
    /**
     * CommandTime
     */
    private Date commandTime;

    public Date getCommandTime() {
        return commandTime;
    }

    public void setCommandTime(Date commandTime) {
        this.commandTime = commandTime;
    }
    
    /**
     * Data
     */
    private String data;

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }
    
}
