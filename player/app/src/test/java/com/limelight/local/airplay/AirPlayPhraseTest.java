package com.limelight.local.airplay;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AirPlayPhraseTest {
    @Test
    public void chineseNameRunsOn() {
        assertEquals("正在连接小林的 iPad", AirPlayState.phrase("正在连接", "小林的 iPad", ""));
    }

    @Test
    public void latinNameIsSpacedOff() {
        assertEquals("正在显示 iPad 的屏幕", AirPlayState.phrase("正在显示", "iPad", "的屏幕"));
        assertEquals("正在显示 Rob's iPhone 的屏幕", AirPlayState.phrase("正在显示", "Rob's iPhone", "的屏幕"));
    }

    @Test
    public void mixedNameIsSpacedWhereItTurnsLatin() {
        assertEquals("正在显示小林的 iPad 的屏幕", AirPlayState.phrase("正在显示", "小林的 iPad", "的屏幕"));
    }

    @Test
    public void noNameIsAnIPad() {
        assertEquals("正在连接 iPad", AirPlayState.phrase("正在连接", null, ""));
        assertEquals("正在连接 iPad", AirPlayState.phrase("正在连接", "  ", ""));
    }
}
