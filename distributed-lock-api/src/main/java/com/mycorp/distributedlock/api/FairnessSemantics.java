package com.mycorp.distributedlock.api;

public enum FairnessSemantics {
    NONE,
    EXCLUSIVE_PREFERRED,
    FIFO_QUEUE
}
