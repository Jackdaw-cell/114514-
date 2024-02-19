/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opengoofy.index12306.biz.ticketservice.service.handler.ticket.filter.query;

import cn.hutool.core.collection.ListUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.google.common.collect.Maps;
import lombok.RequiredArgsConstructor;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.RegionDO;
import org.opengoofy.index12306.biz.ticketservice.dao.entity.StationDO;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.RegionMapper;
import org.opengoofy.index12306.biz.ticketservice.dao.mapper.StationMapper;
import org.opengoofy.index12306.biz.ticketservice.dto.req.TicketPageQueryReqDTO;
import org.opengoofy.index12306.framework.starter.cache.DistributedCache;
import org.opengoofy.index12306.framework.starter.convention.exception.ClientException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;

import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.LOCK_QUERY_ALL_REGION_LIST;
import static org.opengoofy.index12306.biz.ticketservice.common.constant.RedisKeyConstant.QUERY_ALL_REGION_LIST;

/**
 * 购票流程过滤器之验证乘客是否重复购买
 *
 *
 */
@Component
@RequiredArgsConstructor
public class TrainTicketQueryParamVerifyChainFilter implements TrainTicketQueryChainFilter<TicketPageQueryReqDTO> {

    private final RegionMapper regionMapper;
    private final StationMapper stationMapper;
    private final DistributedCache distributedCache;
    private final RedissonClient redissonClient;

    private static boolean FLAG = false;

    @Override
    public void handler(TicketPageQueryReqDTO requestParam) {
        if (requestParam.getDepartureDate().toInstant().atZone(ZoneId.systemDefault()).toLocalDate().isBefore(LocalDate.now())) {
            throw new ClientException("出发日期不能小于当前日期");
        }
        // 验证出发地和目的地是否存在
        StringRedisTemplate stringRedisTemplate = (StringRedisTemplate) distributedCache.getInstance();
        // 查询出发站点和到达站点是否存在，如果不存在也一样属于异常数据

//        HashOperations<String, Object, Object>: 这是获取的 HashOperations 实例，用于执行 Hash 相关的操作。在这里，泛型参数的含义如下：
//        第一个参数 String: 表示 Redis 中 Hash 的 key 的数据类型。
//        第二个参数 Object: 表示 Hash 中的字段（field）的数据类型。
//        第三个参数 Object: 表示 Hash 中的值（value）的数据类型。
        HashOperations<String, Object, Object> hashOperations = stringRedisTemplate.opsForHash();
//        hashOperations: 这是之前获取的 HashOperations 实例，用于执行 Hash 相关的操作。
//
//          multiGet: 这是 HashOperations 提供的方法，用于一次性获取多个字段的值。
//          QUERY_ALL_REGION_LIST: 这是表示 Redis 中 Hash 的 key，即要从哪个 Hash 中获取数据。
//          ListUtil.toList(requestParam.getFromStation(), requestParam.getToStation()): 这是一个包含要获取的字段的列表。通常，这是一个包含多个字段的列表，即要同时获取多个字段的值。requestParam.getFromStation() 和 requestParam.getToStation() 可能是请求参数中的两个站点（站名、地区等）。
//          List<Object> actualExistList: 这是存储获取到的多个字段的值的列表。每个字段的值都会按照它们在请求参数中的顺序存储在这个列表中。
        List<Object> actualExistList = hashOperations.multiGet(
                QUERY_ALL_REGION_LIST,
                ListUtil.toList(requestParam.getFromStation(), requestParam.getToStation())
        );
        // 这里有个毕竟坑的地方，就算为空，也会返回数据，所以我们通过 filter 判断对象是否为空
        long emptyCount = actualExistList.stream().filter(Objects::isNull).count();
        if (emptyCount == 0L) {
            return;
        }
        if ((emptyCount == 2L && FLAG && !distributedCache.hasKey(QUERY_ALL_REGION_LIST))
                || emptyCount == 1L) {
            throw new ClientException("出发地或目的地不存在");
        }
        RLock lock = redissonClient.getLock(LOCK_QUERY_ALL_REGION_LIST);
        lock.lock();
        try {
            if (distributedCache.hasKey(QUERY_ALL_REGION_LIST)) {
                actualExistList = hashOperations.multiGet(
                        QUERY_ALL_REGION_LIST,
                        ListUtil.toList(requestParam.getFromStation(), requestParam.getToStation())
                );
                emptyCount = actualExistList.stream().filter(Objects::nonNull).count();
                if (emptyCount != 2L) {
                    throw new ClientException("出发地或目的地不存在");
                }
                return;
            }
            List<RegionDO> regionDOList = regionMapper.selectList(Wrappers.emptyWrapper());
            List<StationDO> stationDOList = stationMapper.selectList(Wrappers.emptyWrapper());
            HashMap<Object, Object> regionValueMap = Maps.newHashMap();
            for (RegionDO each : regionDOList) {
                regionValueMap.put(each.getCode(), each.getName());
            }
            for (StationDO each : stationDOList) {
                regionValueMap.put(each.getCode(), each.getName());
            }
            hashOperations.putAll(QUERY_ALL_REGION_LIST, regionValueMap);
            FLAG = true;
            actualExistList = hashOperations.multiGet(
                    QUERY_ALL_REGION_LIST,
                    ListUtil.toList(requestParam.getFromStation(), requestParam.getToStation())
            );
            emptyCount = actualExistList.stream().filter(Objects::nonNull).count();
            if (emptyCount != 2L) {
                throw new ClientException("出发地或目的地不存在");
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public int getOrder() {
        return 10;
    }
}
