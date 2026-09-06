package com.skyanchor.bookkeeping.server.server;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** 管理页入口重定向：/admin → /admin/index.html（静态资源）。 */
@Controller
public class AdminPageController {

    @GetMapping("/admin")
    public String admin() {
        return "redirect:/admin/index.html";
    }
}
