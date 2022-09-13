package com.hmdp.utils;

/**
 * @author:Judy
 * @date:2022/9/7 19:17
 * ClassName:ILock
 * package:com.hmdp.utils
 */

/**
 * 分布式锁1.0
 * @author Judy
 */
public interface ILock {
    /**
     * 尝试获取锁
     * @param timeoutSec 设定锁的超时时间
     * @return true=>成功，false=>失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
