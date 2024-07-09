package com.hmdp;


import com.hmdp.service.impl.ShopServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

@SpringBootTest
public class HmDianPingApplicationTest {
    @Resource
    private ShopServiceImpl shopService;

    @Test
    public void save() throws InterruptedException {
        shopService.saveShop2Redis(1L, 10L);
    }

}
