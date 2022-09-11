package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 封装用户信息
     * @param blog
     */
    private void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    /**
     * blog列表
     * @param current
     * @return
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = this.query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
//        // 查询用户
//        //点赞亮不亮
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * blog详情
     * @param id
     * @return
     */
    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("blog不存在");
        }
        //封装
        queryBlogUser(blog);
        //点赞亮不亮
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 封装isLike属性
     * @param blog
     */
    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null){
            //用户未登录
            return;
        }
        Long userId = user.getId();
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key,userId.toString());
        if (score != null){
            blog.setIsLike(true);
        }else {
            blog.setIsLike(false);
        }
//        blog.setIsLike(BooleanUtil.isTrue(member));
    }

    /**
     * 点赞功能
     * @param id
     * @return
     */
    @Override
    public Result likeBlog(Long id) {
        String key = BLOG_LIKED_KEY + id;
        //获取登录用户
        Long userId = UserHolder.getUser().getId();
        //是否点赞
        Double score = stringRedisTemplate.opsForZSet().score(key,userId.toString());
        if (score == null) {
            //没有点赞   点赞操作
            //database +1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if (isSuccess) {
                //redis => set
//                stringRedisTemplate.opsForSet().add(key, String.valueOf(userId));
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //已经点赞  移除点赞
            //database -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //redis !=> set
            if (isSuccess){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 点赞排行榜
     * @param id
     * @return
     */
    @Override
    public Result TOPLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        //查询点赞top5
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        String idStr = StrUtil.join(",", ids);
        //解析userid
        List<UserDTO> userDTOS = userService.query().in("id",ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }
}
