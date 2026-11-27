package io.github.rey5137.topolith.spring.boot.mybatisfixture.mapper;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ItemMapper {
    String find();
}
