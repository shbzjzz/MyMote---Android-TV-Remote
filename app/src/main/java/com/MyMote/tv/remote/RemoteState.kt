package com.MyMote.tv.remote

enum class RemoteState {
    DISCONNECTED,
    CONNECTING,
    TLS_CONNECTED,
    CONFIGURE_SENT,
    CONFIGURED,
    SET_ACTIVE_SENT,
    READY,
    ERROR
}
