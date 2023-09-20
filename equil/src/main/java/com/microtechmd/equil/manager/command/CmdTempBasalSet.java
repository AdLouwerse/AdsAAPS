package com.microtechmd.equil.manager.command;


import com.microtechmd.equil.manager.Utils;

import info.nightscout.shared.logging.LTag;

public class CmdTempBasalSet extends BaseSetting {
    double insulin;
    int step;
    int time;


    public CmdTempBasalSet(double insulin, int time) {
        this.insulin = insulin;
        if (insulin != 0) {
            step = (int) (insulin / 0.05d * 8) / 2;
            this.time = time * 60;
        }
    }

    @Override
    public byte[] getFirstData() {
        aapsLogger.error(LTag.EQUILBLE, "step===" + step + "=====" + time);
        byte[] indexByte = Utils.intToBytes(index2);
        byte[] data2 = new byte[]{0x01, 0x04};
        byte[] data3 = Utils.intToBytes(step);
        byte[] data4 = Utils.intToBytes(time);
        byte[] data = Utils.concat(indexByte, data2, data3, data4);
        index2++;
        return data;
    }

    public byte[] getNextData() {
        byte[] indexByte = Utils.intToBytes(index2);
        byte[] data2 = new byte[]{0x00, 0x04, 0x01};
        byte[] data3 = Utils.intToBytes(0);
        byte[] data = Utils.concat(indexByte, data2, data3);
        index2++;
        return data;
    }

    public void decodeConfirmData(byte[] data) {
        int index = data[4];
        int status = data[6] & 0xff;
        synchronized (this) {
            setCmdStatus(true);
            notify();
        }
    }
}
