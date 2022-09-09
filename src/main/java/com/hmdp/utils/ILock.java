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
    boolean tryLock(long timeoutSec);
    void unlock();
}
