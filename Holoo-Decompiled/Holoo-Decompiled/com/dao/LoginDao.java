/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.dto.LoginDto$LoginDtoBuilder
 *  com.security.TokenStore
 *  com.utility.CallApi
 *  com.utility.DbConnection
 *  com.utility.LockCode
 *  com.utility.LockReader
 *  com.utility.SoftwareInfo
 *  com.utility.TncException
 *  com.utility.TncExceptionMessages
 *  com.utility.TncJdbc
 *  com.utility.TncLog
 *  com.webhook.CDCEngineHandler
 *  com.webhook.util.CDCStateHandler
 *  javax.servlet.http.HttpServletRequest
 *  org.springframework.beans.factory.annotation.Autowired
 *  org.springframework.http.ResponseEntity
 *  org.springframework.jdbc.core.JdbcTemplate
 *  org.springframework.jdbc.support.rowset.SqlRowSet
 *  org.springframework.lang.Nullable
 *  org.springframework.stereotype.Component
 */
package com.dao;

import com.dto.LoginDto;
import com.security.TokenStore;
import com.utility.CallApi;
import com.utility.ConvertStr;
import com.utility.DbConnection;
import com.utility.LockCode;
import com.utility.LockReader;
import com.utility.SoftwareInfo;
import com.utility.TextNormalizer;
import com.utility.TncException;
import com.utility.TncExceptionMessages;
import com.utility.TncJdbc;
import com.utility.TncLog;
import com.webhook.CDCEngineHandler;
import com.webhook.util.CDCStateHandler;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

@Component
public class LoginDao {
    private final TokenStore tokenStore;
    private final DbConnection dbConnection;
    private final CDCEngineHandler cdcEngineHandler;
    private final CDCStateHandler stateHandler;
    private final TncLog log;
    private String key = "";

    @Autowired
    public LoginDao(TokenStore tokenStore, DbConnection dbConnection, CDCEngineHandler cdcEngineHandler, CDCStateHandler stateHandler, TncLog log) {
        this.tokenStore = tokenStore;
        this.dbConnection = dbConnection;
        this.cdcEngineHandler = cdcEngineHandler;
        this.stateHandler = stateHandler;
        this.log = log;
        try {
            LoginDto.LoginDtoBuilder login = LoginDto.builder();
            this.setDataSource(null);
            SqlRowSet rs = this.dbConnection.getJdbcTemplate(null).queryForRowSet(" USE MASTER SELECT [NAME] FROM SYS.DATABASES WHERE [NAME] LIKE 'HOLOO%' AND [NAME] NOT LIKE '%[^a-z][^A-Z]_[^a-z][^A-Z]%' AND IS_CDC_ENABLED = 1");
            while (rs.next()) {
                login.DataBaseName(rs.getString("NAME"));
                try {
                    if (!this.startCDC(login.build())) continue;
                    break;
                }
                catch (Exception exception) {
                }
            }
        }
        catch (Throwable throwable) {
            // empty catch block
        }
    }

    public ResponseEntity<Map<String, Object>> getUserInfo(HttpServletRequest request) {
        LinkedHashMap<String, Boolean> model = new LinkedHashMap<String, Boolean>();
        ArrayList<Map<String, Object>> failure = new ArrayList<Map<String, Object>>();
        ArrayList<Map<String, Object>> success = new ArrayList<Map<String, Object>>();
        LoginDto myLogin = this.dbConnection.setToken();
        String sqlStr = " select [Name], SuperVizor IsAdmin From Userdb U ";
        String sqlWhere = this.getFilter(myLogin, request);
        sqlStr = sqlStr + sqlWhere + "  order by U.Name";
        List rowSet = this.dbConnection.getJdbcTemplate(myLogin).queryForList(sqlStr);
        if (sqlWhere.equals("") || rowSet.isEmpty()) {
            TncException e = new TncException(TncExceptionMessages.INCORRECT_USER_OR_PASS);
            failure.add(e.getErrorMap());
        } else {
            if (String.valueOf(((Map)rowSet.get(0)).get("IsAdmin")).equals("true")) {
                model.put("IsAdmin", true);
            } else {
                model.put("IsAdmin", false);
            }
            success.add(model);
        }
        return ConvertStr.getMapResponseEntity("UserInfo", failure, success);
    }

    public synchronized ResponseEntity<?> setLogin(LoginDto myLogin) {
        try {
            this.setDataSource(null);
            boolean isCreate = false;
            myLogin.setIsMyHoloo(false);
            if (myLogin.getUserName().equals("TncCloud") && !ConvertStr.isNullOrEmpty(myLogin.getSerial())) {
                isCreate = this.dbConnection.createDataBase(myLogin);
                myLogin.setIsMyHoloo(true);
            }
            TncJdbc jdbcTemplate = this.dbConnection.getJdbcTemplate(myLogin);
            this.key = "";
            LockReader lockReader = new LockReader(jdbcTemplate);
            if (!myLogin.getIsMyHoloo().booleanValue()) {
                this.key = lockReader.getKey();
                if (this.key.isEmpty() || this.key.equals("0") || myLogin.getUserPass() == null || myLogin.getUserName() == null || myLogin.getUserPass().equalsIgnoreCase("") || myLogin.getUserName().equalsIgnoreCase("")) {
                    throw new TncException(TncExceptionMessages.LOGIN_FAILED);
                }
            }
            String pass = "";
            String passNew = "";
            String sql = " SELECT [Name], PasWord, UserCodeInc FROM UserDb \n  WHERE [Name]='" + myLogin.getUserName() + "'";
            SqlRowSet rowSet = jdbcTemplate.queryForRowSet(sql);
            if (rowSet.next()) {
                if (myLogin.getIsMyHoloo().booleanValue()) {
                    pass = ConvertStr.base64encode("TNC#@Cweb");
                } else {
                    try {
                        passNew = ConvertStr.base64encode(ConvertStr.decryptText(rowSet.getString("PasWord"), this.key + "2600"));
                    }
                    catch (Exception exception) {
                        // empty catch block
                    }
                    try {
                        pass = ConvertStr.base64encode(ConvertStr.decryptText(rowSet.getString("PasWord"), this.key));
                    }
                    catch (Exception exception) {
                        // empty catch block
                    }
                }
            }
            String username = TextNormalizer.getNormalizedText(rowSet.getString("Name"));
            if (!pass.equals(myLogin.getUserPass()) && !myLogin.getUserPass().equals(passNew) || !Objects.requireNonNull(username).contentEquals(myLogin.getUserName())) {
                throw new TncException(TncExceptionMessages.WRONG_USER_PASS);
            }
            myLogin.setUserCodeInc(rowSet.getInt("UserCodeInc"));
            if (myLogin.isAndroid()) {
                this.prepareAndroid((JdbcTemplate)jdbcTemplate, myLogin);
            }
            sql = " select name from [dbo].syscolumns where id =Object_Id('Userdb')";
            rowSet = jdbcTemplate.queryForRowSet(sql);
            myLogin.setIsTorfehNegar(false);
            while (rowSet.next()) {
                if (!Objects.requireNonNull(rowSet.getString("name")).toUpperCase().equals("SUPERVISSER")) continue;
                myLogin.setIsTorfehNegar(true);
                break;
            }
            if (!myLogin.getIsTorfehNegar().booleanValue() && (rowSet = jdbcTemplate.queryForRowSet(sql = " SELECT [Name], SuperVizor FROM UserDb \n  WHERE [Name]='" + myLogin.getUserName() + "'")).next()) {
                myLogin.setSuperVizor(rowSet.getBoolean("SuperVizor"));
            }
            if (!this.lastLoginDate(myLogin, this.key)) {
                throw new TncException(TncExceptionMessages.RUN_HOLOO_FIRST);
            }
            if (!this.isAccessWebService(myLogin, lockReader)) {
                throw new TncException(TncExceptionMessages.HAVE_NO_PRIVILEGES);
            }
            myLogin.setComplimentaryActive(lockReader.isLockEnabled(LockCode.COMPLEMENTARY_PRODUCTS));
            if (!myLogin.getIsMyHoloo().booleanValue() && (rowSet = jdbcTemplate.queryForRowSet(sql = " select [String] from mSetup2 where name = '1st_SerialNo' ")).next()) {
                myLogin.setSerial(rowSet.getString("String"));
            }
            String token = this.tokenStore.createToken(myLogin);
            LinkedHashMap<String, Object> model = new LinkedHashMap<String, Object>();
            model.put("State", true);
            model.put("Token", token);
            if (isCreate) {
                model.put("IsCreate", true);
            }
            this.dbConnection.runScript(myLogin);
            if (myLogin.getIsMyHoloo().booleanValue()) {
                jdbcTemplate.execute("Update UserDb Set UserCodeInc= -10 Where Name='TncCloud'");
                CallApi.callApiLastChange((LoginDto)myLogin);
            } else {
                this.startCDC(myLogin);
            }
            this.log.info("LOGIN SUCCESS DB = " + myLogin.getDataBaseName());
            return ResponseEntity.ok(Collections.singletonMap("Login", model));
        }
        catch (TncException ex) {
            ex.putMoreInfo("State", (Object)false);
            this.log.info("LOGIN EXCEPTION DB = " + myLogin.getDataBaseName() + " EXCEPTION = " + ex.getMessage());
            return ResponseEntity.ok(Collections.singletonMap("Login", ex.getErrorMap()));
        }
        catch (Exception ex) {
            TncException e = new TncException(TncExceptionMessages.LOGIN_FAILED);
            e.putMoreInfo("State", (Object)false);
            e.putMoreInfo("Token", (Object)"");
            this.log.info("LOGIN EXCEPTION DB = " + myLogin.getDataBaseName() + " EXCEPTION = " + ex.getMessage());
            return ResponseEntity.ok(Collections.singletonMap("Login", e.getErrorMap()));
        }
    }

    private void prepareAndroid(JdbcTemplate jdbcTemplate, LoginDto login) throws TncException {
        SqlRowSet rs = jdbcTemplate.queryForRowSet(" SELECT isNull([Boolean], 0) isDroidActive FROM MSetup2 WHERE [NAME] = 'SWSocket'");
        if (!rs.next() || !rs.getBoolean("isDroidActive")) {
            throw new TncException(TncExceptionMessages.DE_ACTIVE_MOBILE_SERVICE);
        }
        rs = jdbcTemplate.queryForRowSet("!!! SELECT isNull(DBPnlOrderRight, 0) isAndroidDb FROM master..Alias_Holoo WHERE DatabaseNames = ?", new Object[]{login.getDataBaseName()});
        if (!rs.next() || !rs.getBoolean("isAndroidDb")) {
            throw new TncException(TncExceptionMessages.NOT_ANDROID_DB);
        }
        rs = jdbcTemplate.queryForRowSet(" SELECT isNull(DBPnlOrderRight, 0) userHasAccessToAndroid FROM USERDB WHERE UserCodeInc = ?", new Object[]{login.getUserCodeInc()});
        if (rs.next() && !rs.getBoolean("userHasAccessToAndroid")) {
            throw new TncException(TncExceptionMessages.NOT_ANDROID_USER);
        }
    }

    public void setDataSource(@Nullable LoginDto userSerial) throws TncException {
        if (this.dbConnection.isDataSourceNotSet(userSerial)) {
            throw new TncException(TncExceptionMessages.CHECK_CONFIG_UDL);
        }
    }

    public synchronized ResponseEntity<?> logPosLogin(LoginDto login) {
        try {
            this.setDataSource(null);
            String pass = ConvertStr.base64encode("TNC#@Clogpos");
            if (!pass.equals(login.getUserPass())) {
                throw new TncException(TncExceptionMessages.LOGIN_FAILED);
            }
            login.setPosLog(true);
            String token = this.tokenStore.createToken(login);
            LinkedHashMap<String, Object> model = new LinkedHashMap<String, Object>();
            model.put("State", true);
            model.put("Token", token);
            return ResponseEntity.ok(Collections.singletonMap("Login", model));
        }
        catch (TncException ex) {
            ex.putMoreInfo("State", (Object)false);
            return ResponseEntity.ok(Collections.singletonMap("Login", ex.getErrorMap()));
        }
        catch (Exception ex) {
            TncException e = new TncException(TncExceptionMessages.LOGIN_FAILED);
            e.putMoreInfo("State", (Object)false);
            e.putMoreInfo("Token", (Object)"");
            return ResponseEntity.ok(Collections.singletonMap("Login", e.getErrorMap()));
        }
    }

    private boolean startCDC(LoginDto myLogin) {
        try {
            this.stateHandler.createWebHookTableIfNotExist(myLogin);
            if (!this.cdcEngineHandler.isEngineRunning() && !this.stateHandler.isStopState(myLogin)) {
                this.cdcEngineHandler.restartConnector(myLogin);
                return true;
            }
        }
        catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private boolean isAccessWebService(LoginDto myLogin, LockReader lockReader) {
        if (myLogin.getIsMyHoloo().booleanValue() || myLogin.getIsTorfehNegar().booleanValue() || myLogin.isAndroid()) {
            return true;
        }
        return lockReader.isLockEnabled(LockCode.ACCESS_WEB_SERVICE);
    }

    private boolean lastLoginDate(LoginDto myLogin, String key) {
        if (myLogin.getIsMyHoloo().booleanValue() || myLogin.getIsTorfehNegar().booleanValue()) {
            return true;
        }
        SoftwareInfo softInfo = new SoftwareInfo(this.dbConnection.getJdbcTemplate(myLogin), key);
        if (myLogin.isAndroid() && !softInfo.getLockInfo().startsWith("849") && !softInfo.getLockInfo().startsWith("813")) {
            throw new TncException(TncExceptionMessages.HAVE_NO_PRIVILEGES);
        }
        LocalDate runExeDate = null;
        if (softInfo.getRunDate() != null) {
            runExeDate = LocalDate.parse(softInfo.getRunDate(), DateTimeFormatter.ofPattern("yyyy/MM/dd"));
        }
        LocalDate nowDate = LocalDate.now();
        long diffDate = -1L;
        if (runExeDate != null) {
            diffDate = Math.abs(ChronoUnit.DAYS.between(nowDate, runExeDate));
        }
        return runExeDate != null && diffDate < 5L;
    }

    private String[] checkUser(LoginDto myLogin, String userName) {
        TncJdbc jdbcTemplate = this.dbConnection.getJdbcTemplate(myLogin);
        this.key = "";
        LockReader lockReader = new LockReader(jdbcTemplate);
        this.key = lockReader.getKey();
        if (this.key.isEmpty() || this.key.equals("0")) {
            return new String[]{"", ""};
        }
        String pass = "";
        String passNew = "";
        String sql = " select [Name], PasWord, UserCodeInc from UserDb \n  Where [Name] = '" + userName + "'";
        SqlRowSet rowSet = jdbcTemplate.queryForRowSet(sql);
        if (rowSet.next()) {
            try {
                passNew = ConvertStr.decryptText(rowSet.getString("PasWord"), this.key + "2600");
            }
            catch (Exception exception) {
                // empty catch block
            }
            try {
                pass = ConvertStr.decryptText(rowSet.getString("PasWord"), this.key);
            }
            catch (Exception exception) {
                // empty catch block
            }
        }
        return new String[]{pass, passNew};
    }

    public String getFilter(LoginDto myLogin, HttpServletRequest request) {
        String sqlWhere = "";
        Map<String, String> info = ConvertStr.rebuildQueryString(request);
        if (!info.containsKey("username") || !info.containsKey("userpass")) {
            return "";
        }
        if (info.get("username").isEmpty() || info.get("userpass").isEmpty()) {
            return "";
        }
        String[] passArray = this.checkUser(myLogin, info.get("username"));
        String pass = ConvertStr.base64encode(passArray[0]);
        String passNew = ConvertStr.base64encode(passArray[1]);
        boolean isPassNew = info.get("userpass").equals(passNew);
        if (info.get("userpass").equals(pass) || info.get("userpass").equals(passNew)) {
            sqlWhere = sqlWhere + " Where  Isnull(U.Name,'') = '" + info.get("username") + "' And  Isnull(U.PasWord,'') = '" + ConvertStr.encryptText(isPassNew ? passArray[1] : passArray[0], isPassNew ? this.key + "2600" : this.key) + "'";
        }
        return sqlWhere;
    }
}
