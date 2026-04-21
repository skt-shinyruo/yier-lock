package com.mycorp.distributedlock.zookeeper;

import org.apache.curator.framework.CuratorFramework;

interface CuratorBackedSession {

    CuratorFramework curatorFramework();
}
