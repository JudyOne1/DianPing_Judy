package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {
    @Autowired
    private IFollowService followService;

    @GetMapping("/common/{id}")
    public Result followCommoms(@PathVariable Long id){
        return followService.followCommons(id);
    }


    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long id,@PathVariable("isFollow") Boolean isFollow){
        return followService.follow(id,isFollow);
    }

    @GetMapping("/or/not/{id}")
    public Result followOrNot(@PathVariable Long id){
        return followService.followOrNot(id);
    }
}
