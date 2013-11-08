/*
 * Copyright (c) 2012-2013 by Jens V. Fischer, Zuse Institute Berlin
 *               
 *
 * Licensed under the BSD License, see LICENSE file for details.
 *
 */

package org.xtreemfs.common.benchmark;

import org.xtreemfs.common.libxtreemfs.Options;
import org.xtreemfs.foundation.SSLOptions;
import org.xtreemfs.foundation.logging.Logging;
import org.xtreemfs.foundation.pbrpc.generatedinterfaces.RPC;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Datastructure holding all parameters for the benchmark library.
 * <p/>
 *
 * For documentation of the parameters and default values see {@link ConfigBuilder}.
 * <p/>
 * 
 * {@link ConfigBuilder} should be used to build this (the given default values are only present if the builder is used
 * <p/>
 * 
 * The {@link Controller}, the {@link ConfigBuilder} and {@link Config} represent the API to the benchmark library.
 * 
 * @author jensvfischer
 */
public class Config {

    private final long                basefileSizeInBytes;
    private final int                 filesize;
    private final String              userName;
    private final String              group;
    private final String              adminPassword;
    private final String              dirAddress;
    private final RPC.UserCredentials userCredentials;
    private final RPC.Auth            auth;
    private final SSLOptions          sslOptions;
    private final Options             options;
    private final String              osdSelectionPolicies;
    private final Map<String, String> policyAttributes;
    private final int                 chunkSizeInBytes;
    private int                       stripeSizeInBytes;
    private final boolean             stripeSizeSet;
    private int                       stripeWidth;
    private final boolean             stripeWidthSet;
    private final boolean             noCleanup;
    private final boolean             noCleanupOfVolumes;
    private final boolean             noCleanupOfBasefile;
    private final boolean             osdCleanup;

    /**
     * Build Params from {@link ConfigBuilder}. Should only be called from
     * {@link ConfigBuilder#build()}
     * 
     * @param builder
     * @throws Exception
     */
    public Config(ConfigBuilder builder) throws Exception {
        this.basefileSizeInBytes = builder.getBasefileSizeInBytes();
        this.filesize = builder.getFilesize();
        this.userName = builder.getUserName();
        this.group = builder.getGroup();
        this.adminPassword = builder.getAdminPassword();
        this.dirAddress = builder.getDirAddress();
        this.userCredentials = RPC.UserCredentials.newBuilder().setUsername(builder.getUserName()).addGroups(builder.getGroup())
                .build();
        this.sslOptions = builder.getSslOptions();
        this.options = builder.getOptions();
        this.osdSelectionPolicies = builder.getOsdSelectionPolicies();
        this.policyAttributes = builder.getPolicyAttributes();
        this.chunkSizeInBytes = builder.getChunkSizeInBytes();
        this.stripeSizeInBytes = builder.getStripeSizeInBytes();
        this.stripeSizeSet = builder.isStripeSizeSet();
        this.stripeWidth = builder.getStripeWidth();
        this.stripeWidthSet = builder.isStripeWidthSet();
        this.auth = builder.getAuth();
        this.noCleanup = builder.isNoCleanup();
        this.noCleanupOfVolumes = builder.isNoCleanupOfVolumes();
        this.noCleanupOfBasefile = builder.isNoCleanupOfBasefile();
        this.osdCleanup = builder.isOsdCleanup();
    }


    /* Build string with all the instance parameters as key-value pairs */
    private String getAllValues() throws IllegalAccessException {
        Field[] fields = Config.class.getDeclaredFields();
        StringBuffer result = new StringBuffer();
        for (Field field : fields) {
            String name = field.getName();
            Object value = field.get(this);
            if (name != "userCredentials") {
                result.append(name + ": " + value + ";\n");
            }
        }
        return result.toString();
    }

    @Override
    public String toString() {
        try {
            return getAllValues();
        } catch (IllegalAccessException e) {
            Logging.logMessage(Logging.LEVEL_ERROR, Logging.Category.tool, this,
                    "Access to Params fields not possible.");
            Logging.logError(Logging.LEVEL_ERROR, Logging.Category.tool, e);
        }
        return "Access not possible";
    }

    void setStripeSizeInBytes(int stripeSizeInBytes) {
        this.stripeSizeInBytes = stripeSizeInBytes;
    }

    void setStripeWidth(int stripeWidth) {
        this.stripeWidth = stripeWidth;
    }

    long getBasefileSizeInBytes() {
        return basefileSizeInBytes;
    }

    int getFilesize() {
        return filesize;
    }

    String getUserName() {
        return userName;
    }

    String getGroup() {
        return group;
    }

    String getAdminPassword() {
        return adminPassword;
    }

    String getDirAddress() {
        return dirAddress;
    }

    RPC.UserCredentials getUserCredentials() {
        return userCredentials;
    }

    RPC.Auth getAuth() {
        return auth;
    }

    SSLOptions getSslOptions() {
        return sslOptions;
    }

    Options getOptions() {
        return options;
    }

    String getOsdSelectionPolicies() {
        return osdSelectionPolicies;
    }

    Map<String, String> getPolicyAttributes() {
        return policyAttributes;
    }

    int getChunkSizeInBytes() {
        return chunkSizeInBytes;
    }

    int getStripeSizeInBytes() {
        return stripeSizeInBytes;
    }

    int getStripeSizeInKiB() {
        return stripeSizeInBytes/1024;
    }

    int getStripeWidth() {
        return stripeWidth;
    }

    boolean isNoCleanup() {
        return noCleanup;
    }

    boolean isNoCleanupOfVolumes() {
        return noCleanupOfVolumes;
    }

    boolean isNoCleanupOfBasefile() {
        return noCleanupOfBasefile;
    }

    boolean isOsdCleanup() {
        return osdCleanup;
    }

    boolean isStripeSizeSet() {
        return stripeSizeSet;
    }

    boolean isStripeWidthSet() {
        return stripeWidthSet;
    }
}