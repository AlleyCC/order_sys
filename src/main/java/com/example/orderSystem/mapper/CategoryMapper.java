package com.example.orderSystem.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.orderSystem.entity.Category;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CategoryMapper extends BaseMapper<Category> {
}
