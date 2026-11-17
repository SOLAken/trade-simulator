package com.tradesim.user;

import com.tradesim.common.web.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** 经 Nacos 服务发现 + LoadBalancer 调用 account-service 内部接口（网关不暴露 /internal/**）。 */
@FeignClient(name = "account-service", path = "/internal/account")
public interface AccountClient {
    @PostMapping("/open")
    Result<Boolean> open(@RequestParam("userId") long userId, @RequestParam("contestId") long contestId,
                         @RequestParam("initCash") long initCash);
}
