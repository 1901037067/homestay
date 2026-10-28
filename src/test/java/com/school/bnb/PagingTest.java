package com.school.bnb;

import static org.assertj.core.api.Assertions.assertThat;

import com.school.bnb.common.Paging;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分页参数兜底。纯单测、不起容器 —— 这个小工具存在的唯一理由就是"别让 size=100000 把整表拉进内存"，
 * 而那件事只能靠边界值断言来钉。
 */
class PagingTest {

    @Test
    @DisplayName("size 上限 200、下限 1；页码不小于 1")
    void clampsSizeAndPage() {
        assertThat(Paging.size(100_000)).isEqualTo(200);
        assertThat(Paging.size(200)).isEqualTo(200);
        assertThat(Paging.size(20)).isEqualTo(20);
        assertThat(Paging.size(0)).as("0 或负数按 1 处理，别返回空页让人以为没数据").isEqualTo(1);
        assertThat(Paging.size(-5)).isEqualTo(1);

        assertThat(Paging.page(0)).isEqualTo(1);
        assertThat(Paging.page(-3)).isEqualTo(1);
        assertThat(Paging.page(7)).isEqualTo(7);
    }
}
