package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 *  服务类
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException;

    Result queryById(Long id);

    Result update(Shop shop);
}
