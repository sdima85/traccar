/*
 * Copyright 2012 Anton Tananaev (anton.tananaev@gmail.com)
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
package org.traccar;

import java.util.List;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.timeout.IdleStateAwareChannelHandler;
import org.jboss.netty.handler.timeout.IdleStateEvent;
import org.traccar.helper.Log;
import org.traccar.database.DataManager;
import org.traccar.model.DeviceCommand;
import org.traccar.model.Position;

/**
 * Tracker message handler
 */
@ChannelHandler.Sharable
public class TrackerEventHandler extends IdleStateAwareChannelHandler {

    /**
     * Data manager
     */
    private DataManager dataManager;

    TrackerEventHandler(DataManager newDataManager) {
        dataManager = newDataManager;
    }

    private Long processSinglePosition(Position position) {
        if (position == null) {
            Log.info("processSinglePosition null message");
        } else {
            StringBuilder s = new StringBuilder();
            s.append("device: ").append(position.getDeviceId()).append(", ");
            s.append("imei: ").append(position.getImei()).append(", ");            
            s.append("time: ").append(position.getTime()).append(", ");
            s.append("lat: ").append(position.getLatitude()).append(", ");
            s.append("lon: ").append(position.getLongitude());
            Log.info(s.toString());
        }

        // Write position to database
        Long id = null;
        try {
            id = dataManager.addPosition(position);
        } catch (Exception error) {
            Log.warning(error);
            
        }
        return id;
    }
    
    private void processSingleCommand(DeviceCommand command) {
        if (command == null) {
            Log.info("processSingleCommand null message");
        } else {
            StringBuilder s = new StringBuilder();
            s.append("device: ").append(command.getDeviceId()).append(", ");
            s.append("imei: ").append(command.getImei()).append(", ");            
            s.append("time: ").append(command.getCommandTime()).append(", ");
            s.append("data: ").append(command.getData());
            Log.info(s.toString());
        }

        // Write command to database
        Long id = null;
        try {
            id = dataManager.addCommand(command);
        } catch (Exception error) {
            Log.warning(error);
            
        }
        //return id;
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) {
        Long id = null;
        Position lastPostition = null;
        if (e.getMessage() instanceof Position) {
            id = processSinglePosition((Position) e.getMessage());
            lastPostition = (Position) e.getMessage();
        } else if (e.getMessage() instanceof List) {
            
            List<Object> datas = (List<Object>) e.getMessage();
            for (Object data : (List<Object>) datas){
                if (data instanceof Position){
                    id = processSinglePosition((Position) data);
                    lastPostition = (Position)data;
                } else if (data instanceof DeviceCommand){
                    processSingleCommand((DeviceCommand) data);
                }
            }
            
            //List<Position> positions = (List<Position>) e.getMessage();
            //for (Position position : positions) {
            //    id = processSinglePosition(position);
            //    lastPostition = position;
            //}
        } else if (e.getMessage() instanceof DeviceCommand) {
            processSingleCommand((DeviceCommand) e.getMessage());
        }
        
        if (id != null && lastPostition != null) {
            try {
                dataManager.updateLatestPosition(lastPostition, id);
            } catch (Exception error) {
                Log.warning(error);
            }
        }
    }

    @Override
    public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) {
        Log.info("Closing connection by disconnect");
        e.getChannel().close();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) {
        Log.info("Closing connection by exception");
        e.getChannel().close();
    }

    @Override
    public void channelIdle(ChannelHandlerContext ctx, IdleStateEvent e) {
        Log.info("Closing connection by timeout");
        e.getChannel().close();
    }

}
