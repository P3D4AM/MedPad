/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  com.dto.LoginDto$LoginDtoBuilder
 *  org.springframework.security.core.context.SecurityContextHolder
 */
package com.dto;

import com.dto.LoginDto;
import com.utility.ConvertStr;
import org.springframework.security.core.context.SecurityContextHolder;

public class LoginDto {
    private String UserName;
    private String UserPass;
    private String DataBaseName;
    private int subsystemid;
    private String Serial;
    private Boolean IsMyHoloo;
    private transient boolean isPosLog;
    private Boolean IsTorfehNegar;
    private boolean isAndroid;
    private Boolean SuperVizor;
    private int UserCodeInc;
    private boolean complimentaryActive;

    public static LoginDto makeFromCurrentToken() {
        return (LoginDto)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    public String makeUseDbSql() {
        if (ConvertStr.isNullOrEmpty(this.DataBaseName)) {
            return " USE MASTER \n ";
        }
        return " USE " + this.DataBaseName + " \n ";
    }

    public Boolean getSuperVizor() {
        return this.SuperVizor == null ? false : this.SuperVizor;
    }

    public String getLoginUniqueId() {
        return this.UserName + this.DataBaseName + this.Serial;
    }

    public String getJdbcKey() {
        return (this.UserName + this.UserPass + this.DataBaseName + this.Serial).toLowerCase();
    }

    private static String $default$UserName() {
        return "";
    }

    private static String $default$UserPass() {
        return "";
    }

    private static String $default$DataBaseName() {
        return "";
    }

    private static String $default$Serial() {
        return "";
    }

    private static int $default$UserCodeInc() {
        return 0;
    }

    LoginDto(String UserName, String UserPass, String DataBaseName, int subsystemid, String Serial, Boolean IsMyHoloo, boolean isPosLog, Boolean IsTorfehNegar, boolean isAndroid, Boolean SuperVizor, int UserCodeInc, boolean complimentaryActive) {
        this.UserName = UserName;
        this.UserPass = UserPass;
        this.DataBaseName = DataBaseName;
        this.subsystemid = subsystemid;
        this.Serial = Serial;
        this.IsMyHoloo = IsMyHoloo;
        this.isPosLog = isPosLog;
        this.IsTorfehNegar = IsTorfehNegar;
        this.isAndroid = isAndroid;
        this.SuperVizor = SuperVizor;
        this.UserCodeInc = UserCodeInc;
        this.complimentaryActive = complimentaryActive;
    }

    public static LoginDtoBuilder builder() {
        return new LoginDtoBuilder();
    }

    public LoginDtoBuilder toBuilder() {
        return new LoginDtoBuilder().UserName(this.UserName).UserPass(this.UserPass).DataBaseName(this.DataBaseName).subsystemid(this.subsystemid).Serial(this.Serial).IsMyHoloo(this.IsMyHoloo).isPosLog(this.isPosLog).IsTorfehNegar(this.IsTorfehNegar).isAndroid(this.isAndroid).SuperVizor(this.SuperVizor).UserCodeInc(this.UserCodeInc).complimentaryActive(this.complimentaryActive);
    }

    public String getUserName() {
        return this.UserName;
    }

    public String getUserPass() {
        return this.UserPass;
    }

    public String getDataBaseName() {
        return this.DataBaseName;
    }

    public int getSubsystemid() {
        return this.subsystemid;
    }

    public String getSerial() {
        return this.Serial;
    }

    public Boolean getIsMyHoloo() {
        return this.IsMyHoloo;
    }

    public boolean isPosLog() {
        return this.isPosLog;
    }

    public Boolean getIsTorfehNegar() {
        return this.IsTorfehNegar;
    }

    public boolean isAndroid() {
        return this.isAndroid;
    }

    public int getUserCodeInc() {
        return this.UserCodeInc;
    }

    public boolean isComplimentaryActive() {
        return this.complimentaryActive;
    }

    public void setUserName(String UserName) {
        this.UserName = UserName;
    }

    public void setUserPass(String UserPass) {
        this.UserPass = UserPass;
    }

    public void setDataBaseName(String DataBaseName) {
        this.DataBaseName = DataBaseName;
    }

    public void setSubsystemid(int subsystemid) {
        this.subsystemid = subsystemid;
    }

    public void setSerial(String Serial) {
        this.Serial = Serial;
    }

    public void setIsMyHoloo(Boolean IsMyHoloo) {
        this.IsMyHoloo = IsMyHoloo;
    }

    public void setPosLog(boolean isPosLog) {
        this.isPosLog = isPosLog;
    }

    public void setIsTorfehNegar(Boolean IsTorfehNegar) {
        this.IsTorfehNegar = IsTorfehNegar;
    }

    public void setAndroid(boolean isAndroid) {
        this.isAndroid = isAndroid;
    }

    public void setSuperVizor(Boolean SuperVizor) {
        this.SuperVizor = SuperVizor;
    }

    public void setUserCodeInc(int UserCodeInc) {
        this.UserCodeInc = UserCodeInc;
    }

    public void setComplimentaryActive(boolean complimentaryActive) {
        this.complimentaryActive = complimentaryActive;
    }

    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof LoginDto)) {
            return false;
        }
        LoginDto other = (LoginDto)o;
        if (!other.canEqual(this)) {
            return false;
        }
        if (this.getSubsystemid() != other.getSubsystemid()) {
            return false;
        }
        if (this.isAndroid() != other.isAndroid()) {
            return false;
        }
        if (this.getUserCodeInc() != other.getUserCodeInc()) {
            return false;
        }
        if (this.isComplimentaryActive() != other.isComplimentaryActive()) {
            return false;
        }
        Boolean this$IsMyHoloo = this.getIsMyHoloo();
        Boolean other$IsMyHoloo = other.getIsMyHoloo();
        if (this$IsMyHoloo == null ? other$IsMyHoloo != null : !((Object)this$IsMyHoloo).equals(other$IsMyHoloo)) {
            return false;
        }
        Boolean this$IsTorfehNegar = this.getIsTorfehNegar();
        Boolean other$IsTorfehNegar = other.getIsTorfehNegar();
        if (this$IsTorfehNegar == null ? other$IsTorfehNegar != null : !((Object)this$IsTorfehNegar).equals(other$IsTorfehNegar)) {
            return false;
        }
        Boolean this$SuperVizor = this.getSuperVizor();
        Boolean other$SuperVizor = other.getSuperVizor();
        if (this$SuperVizor == null ? other$SuperVizor != null : !((Object)this$SuperVizor).equals(other$SuperVizor)) {
            return false;
        }
        String this$UserName = this.getUserName();
        String other$UserName = other.getUserName();
        if (this$UserName == null ? other$UserName != null : !this$UserName.equals(other$UserName)) {
            return false;
        }
        String this$UserPass = this.getUserPass();
        String other$UserPass = other.getUserPass();
        if (this$UserPass == null ? other$UserPass != null : !this$UserPass.equals(other$UserPass)) {
            return false;
        }
        String this$DataBaseName = this.getDataBaseName();
        String other$DataBaseName = other.getDataBaseName();
        if (this$DataBaseName == null ? other$DataBaseName != null : !this$DataBaseName.equals(other$DataBaseName)) {
            return false;
        }
        String this$Serial = this.getSerial();
        String other$Serial = other.getSerial();
        return !(this$Serial == null ? other$Serial != null : !this$Serial.equals(other$Serial));
    }

    protected boolean canEqual(Object other) {
        return other instanceof LoginDto;
    }

    public int hashCode() {
        int PRIME = 59;
        int result = 1;
        result = result * 59 + this.getSubsystemid();
        result = result * 59 + (this.isAndroid() ? 79 : 97);
        result = result * 59 + this.getUserCodeInc();
        result = result * 59 + (this.isComplimentaryActive() ? 79 : 97);
        Boolean $IsMyHoloo = this.getIsMyHoloo();
        result = result * 59 + ($IsMyHoloo == null ? 43 : ((Object)$IsMyHoloo).hashCode());
        Boolean $IsTorfehNegar = this.getIsTorfehNegar();
        result = result * 59 + ($IsTorfehNegar == null ? 43 : ((Object)$IsTorfehNegar).hashCode());
        Boolean $SuperVizor = this.getSuperVizor();
        result = result * 59 + ($SuperVizor == null ? 43 : ((Object)$SuperVizor).hashCode());
        String $UserName = this.getUserName();
        result = result * 59 + ($UserName == null ? 43 : $UserName.hashCode());
        String $UserPass = this.getUserPass();
        result = result * 59 + ($UserPass == null ? 43 : $UserPass.hashCode());
        String $DataBaseName = this.getDataBaseName();
        result = result * 59 + ($DataBaseName == null ? 43 : $DataBaseName.hashCode());
        String $Serial = this.getSerial();
        result = result * 59 + ($Serial == null ? 43 : $Serial.hashCode());
        return result;
    }

    public String toString() {
        return "LoginDto(UserName=" + this.getUserName() + ", UserPass=" + this.getUserPass() + ", DataBaseName=" + this.getDataBaseName() + ", subsystemid=" + this.getSubsystemid() + ", Serial=" + this.getSerial() + ", IsMyHoloo=" + this.getIsMyHoloo() + ", isPosLog=" + this.isPosLog() + ", IsTorfehNegar=" + this.getIsTorfehNegar() + ", isAndroid=" + this.isAndroid() + ", SuperVizor=" + this.getSuperVizor() + ", UserCodeInc=" + this.getUserCodeInc() + ", complimentaryActive=" + this.isComplimentaryActive() + ")";
    }

    static /* synthetic */ String access$000() {
        return LoginDto.$default$UserName();
    }

    static /* synthetic */ String access$100() {
        return LoginDto.$default$UserPass();
    }

    static /* synthetic */ String access$200() {
        return LoginDto.$default$DataBaseName();
    }

    static /* synthetic */ String access$300() {
        return LoginDto.$default$Serial();
    }

    static /* synthetic */ int access$400() {
        return LoginDto.$default$UserCodeInc();
    }
}
