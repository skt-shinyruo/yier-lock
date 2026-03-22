package com.mycorp.distributedlock.runtime.spi;

public record BackendContext(String backendId, Object configuration) {
}
