package com.heima.wemedia.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.heima.model.wemedia.pojos.WmSensitive;
import org.apache.ibatis.annotations.Mapper;

/**
 * 敏感词mapper
 */
@Mapper
public interface WmSensitiveMapper extends BaseMapper<WmSensitive> {
}