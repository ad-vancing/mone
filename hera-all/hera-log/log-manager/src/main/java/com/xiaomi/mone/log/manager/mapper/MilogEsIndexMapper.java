package com.xiaomi.mone.log.manager.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xiaomi.mone.log.manager.model.pojo.MilogEsIndexDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author wanghaoyang
 * @since 2021-09-18
 */
@Mapper
public interface MilogEsIndexMapper extends BaseMapper<MilogEsIndexDO> {

    /**
     * 查询region下的所有es索引
     * @param region
     * @return
     */
    List<MilogEsIndexDO> selectRegionIndexList(@Param(value = "region") String region);

    /**
     * 查询区域下所有的es索引
     * @param area
     * @return
     */
    List<MilogEsIndexDO> selectAreaIndexList(@Param(value = "area") String area);
}
