package com.hmdp.dto;

import lombok.Data;

import java.util.List;

/**
 * @author dongdong
 */
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
