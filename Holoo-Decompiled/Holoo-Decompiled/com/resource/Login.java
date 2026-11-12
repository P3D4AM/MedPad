/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.Application
 *  com.dto.LoginDto$LoginDtoBuilder
 *  com.utility.TncLog
 *  javax.servlet.http.HttpServletRequest
 *  org.json.JSONArray
 *  org.json.JSONObject
 *  org.springframework.http.ResponseEntity
 *  org.springframework.web.bind.annotation.GetMapping
 *  org.springframework.web.bind.annotation.PostMapping
 *  org.springframework.web.bind.annotation.RequestBody
 *  org.springframework.web.bind.annotation.RequestMapping
 *  org.springframework.web.bind.annotation.RequestParam
 *  org.springframework.web.bind.annotation.RestController
 */
package com.resource;

import com.Application;
import com.dao.LoginDao;
import com.dto.LoginDto;
import com.utility.ConvertStr;
import com.utility.TncLog;
import javax.servlet.http.HttpServletRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value={"/Login"})
public class Login {
    private final LoginDao loginDao;
    private final Application config;
    private final TncLog log;

    public Login(LoginDao loginDao, Application config, TncLog log) {
        this.loginDao = loginDao;
        this.config = config;
        this.log = log;
    }

    @PostMapping(consumes={"application/json"})
    public synchronized ResponseEntity<?> login(@RequestBody String body) throws Exception {
        JSONObject obj = new JSONObject(body);
        obj = ConvertStr.rebuildJsonObject(obj);
        LoginDto.LoginDtoBuilder myLogin = LoginDto.builder().DataBaseName("").UserName("").UserPass("").Serial("").UserCodeInc(0);
        JSONArray arr = obj.getJSONArray("userinfo");
        JSONObject jo = arr.getJSONObject(0);
        if (jo.has("username")) {
            myLogin.UserName(jo.getString("username"));
        }
        if (jo.has("userpass")) {
            myLogin.UserPass(jo.getString("userpass"));
        }
        if (jo.has("dbname")) {
            myLogin.DataBaseName(jo.getString("dbname"));
        }
        if (jo.has("subsystemid")) {
            myLogin.subsystemid(jo.getInt("subsystemid"));
        }
        if (jo.has("serial")) {
            String serial = jo.getString("serial");
            myLogin.Serial(serial);
            this.log.info("NEW LOGIN SERIAL: " + serial);
        }
        if (jo.has("isandroid")) {
            myLogin.isAndroid(this.config.isAndroidPack());
        }
        if (!jo.getString("username").equals("TNCPosLog")) {
            return this.loginDao.setLogin(myLogin.build());
        }
        return this.loginDao.logPosLogin(myLogin.build());
    }

    @PostMapping(consumes={"application/x-www-form-urlencoded"})
    public synchronized ResponseEntity<?> login(@RequestParam(value="dbname") String dbname, @RequestParam(value="username") String username, @RequestParam(value="userpass") String userpass) {
        LoginDto myLogin = LoginDto.builder().DataBaseName(dbname).UserName(username).UserPass(userpass).UserCodeInc(0).build();
        return this.loginDao.setLogin(myLogin);
    }

    @GetMapping(value={"/UserInfo"})
    public ResponseEntity<?> getUserInfo(HttpServletRequest request) {
        if (!this.config.usePack1) {
            return null;
        }
        return this.loginDao.getUserInfo(request);
    }
}
