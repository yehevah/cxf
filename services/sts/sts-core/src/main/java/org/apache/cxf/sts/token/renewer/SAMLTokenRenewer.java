/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.sts.token.renewer;

import java.security.Principal;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.callback.CallbackHandler;
import javax.xml.ws.handler.MessageContext;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.helpers.CastUtils;
import org.apache.cxf.helpers.DOMUtils;
import org.apache.cxf.security.transport.TLSSessionInfo;
import org.apache.cxf.sts.STSConstants;
import org.apache.cxf.sts.STSPropertiesMBean;
import org.apache.cxf.sts.SignatureProperties;
import org.apache.cxf.sts.cache.CacheUtils;
import org.apache.cxf.sts.request.ReceivedToken;
import org.apache.cxf.sts.request.ReceivedToken.STATE;
import org.apache.cxf.sts.token.provider.ConditionsProvider;
import org.apache.cxf.sts.token.provider.DefaultConditionsProvider;
import org.apache.cxf.sts.token.provider.TokenProviderParameters;
import org.apache.cxf.sts.token.realm.SAMLRealm;
import org.apache.cxf.ws.security.sts.provider.STSException;
import org.apache.cxf.ws.security.tokenstore.SecurityToken;
import org.apache.cxf.ws.security.tokenstore.TokenStore;
import org.apache.cxf.ws.security.wss4j.policyvalidators.AbstractSamlPolicyValidator;
import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.ext.WSPasswordCallback;
import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.common.saml.SAMLKeyInfo;
import org.apache.wss4j.common.saml.SamlAssertionWrapper;
import org.apache.wss4j.common.saml.bean.ConditionsBean;
import org.apache.wss4j.common.saml.builder.SAML1ComponentBuilder;
import org.apache.wss4j.common.saml.builder.SAML2ComponentBuilder;
import org.apache.wss4j.dom.WSConstants;
import org.apache.wss4j.dom.WSDocInfo;
import org.apache.wss4j.dom.WSSConfig;
import org.apache.wss4j.dom.WSSecurityEngineResult;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.handler.WSHandlerConstants;
import org.apache.wss4j.dom.handler.WSHandlerResult;
import org.apache.wss4j.dom.saml.WSSSAMLKeyInfoProcessor;
import org.apache.wss4j.dom.util.WSSecurityUtil;
import org.apache.xml.security.stax.impl.util.IDGenerator;
import org.joda.time.DateTime;
import org.opensaml.common.SAMLVersion;
import org.opensaml.saml1.core.Audience;
import org.opensaml.saml1.core.AudienceRestrictionCondition;
import org.opensaml.saml2.core.AudienceRestriction;

/**
 * A TokenRenewer implementation that renews a (valid or expired) SAML Token.
 */
public class SAMLTokenRenewer implements TokenRenewer {
    
    // The default maximum expired time a token is allowed to be is 30 minutes
    public static final long DEFAULT_MAX_EXPIRY = 60L * 30L;
    
    private static final Logger LOG = LogUtils.getL7dLogger(SAMLTokenRenewer.class);
    private boolean signToken = true;
    private ConditionsProvider conditionsProvider = new DefaultConditionsProvider();
    private Map<String, SAMLRealm> realmMap = new HashMap<String, SAMLRealm>();
    private long maxExpiry = DEFAULT_MAX_EXPIRY;
    // boolean to enable/disable the check of proof of possession
    private boolean verifyProofOfPossession = true;
    private boolean allowRenewalAfterExpiry;
    
    /**
     * Return true if this TokenRenewer implementation is able to renew a token.
     */
    public boolean canHandleToken(ReceivedToken renewTarget) {
        return canHandleToken(renewTarget, null);
    }
    
    /**
     * Return true if this TokenRenewer implementation is able to renew a token in the given realm.
     */
    public boolean canHandleToken(ReceivedToken renewTarget, String realm) {
        if (realm != null && !realmMap.containsKey(realm)) {
            return false;
        }
        Object token = renewTarget.getToken();
        if (token instanceof Element) {
            Element tokenElement = (Element)token;
            String namespace = tokenElement.getNamespaceURI();
            String localname = tokenElement.getLocalName();
            if ((WSConstants.SAML_NS.equals(namespace) || WSConstants.SAML2_NS.equals(namespace))
                && "Assertion".equals(localname)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Set whether proof of possession is required or not to renew a token
     */
    public void setVerifyProofOfPossession(boolean verifyProofOfPossession) {
        this.verifyProofOfPossession = verifyProofOfPossession;
    }
    
    /**
     * Get whether we allow renewal after expiry. The default is false.
     */
    public boolean isAllowRenewalAfterExpiry() {
        return allowRenewalAfterExpiry;
    }

    /**
     * Set whether we allow renewal after expiry. The default is false.
     */
    public void setAllowRenewalAfterExpiry(boolean allowRenewalAfterExpiry) {
        this.allowRenewalAfterExpiry = allowRenewalAfterExpiry;
    }
    
    /**
     * Set a new value (in seconds) for how long a token is allowed to be expired for before renewal. 
     * The default is 30 minutes.
     */
    public void setMaxExpiry(long newExpiry) {
        maxExpiry = newExpiry;
    }
    
    /**
     * Get how long a token is allowed to be expired for before renewal (in seconds). The default is 
     * 30 minutes.
     */
    public long getMaxExpiry() {
        return maxExpiry;
    }
    
    /**
     * Renew a token given a TokenRenewerParameters
     */
    public TokenRenewerResponse renewToken(TokenRenewerParameters tokenParameters) {
        TokenRenewerResponse response = new TokenRenewerResponse();
        ReceivedToken tokenToRenew = tokenParameters.getToken();
        if (tokenToRenew == null || tokenToRenew.getToken() == null
            || (tokenToRenew.getState() != STATE.EXPIRED && tokenToRenew.getState() != STATE.VALID)) {
            LOG.log(Level.WARNING, "The token to renew is null or invalid");
            throw new STSException(
                "The token to renew is null or invalid", STSException.INVALID_REQUEST
            );
        }
        
        TokenStore tokenStore = tokenParameters.getTokenStore();
        if (tokenStore == null) {
            LOG.log(Level.FINE, "A cache must be configured to use the SAMLTokenRenewer");
            throw new STSException("Can't renew SAML assertion", STSException.REQUEST_FAILED);
        }
        
        try {
            SamlAssertionWrapper assertion = new SamlAssertionWrapper((Element)tokenToRenew.getToken());
            
            byte[] oldSignature = assertion.getSignatureValue();
            int hash = Arrays.hashCode(oldSignature);
            SecurityToken cachedToken = tokenStore.getToken(Integer.toString(hash));
            if (cachedToken == null) {
                LOG.log(Level.FINE, "The token to be renewed must be stored in the cache");
                throw new STSException("Can't renew SAML assertion", STSException.REQUEST_FAILED);
            }
            
            // Validate the Assertion
            validateAssertion(assertion, tokenToRenew, cachedToken, tokenParameters);
            
            SamlAssertionWrapper renewedAssertion = new SamlAssertionWrapper(assertion.getXmlObject());
            String oldId = createNewId(renewedAssertion);
            // Remove the previous token (now expired) from the cache
            tokenStore.remove(oldId);
            tokenStore.remove(Integer.toString(hash));
            
            // Create new Conditions & sign the Assertion
            createNewConditions(renewedAssertion, tokenParameters);
            signAssertion(renewedAssertion, tokenParameters);
            
            Document doc = DOMUtils.createDocument();
            Element token = renewedAssertion.toDOM(doc);
            if (renewedAssertion.getSaml1() != null) {
                token.setIdAttributeNS(null, "AssertionID", true);
            } else {
                token.setIdAttributeNS(null, "ID", true);
            }
            doc.appendChild(token);
            
            // Cache the token
            storeTokenInCache(
                tokenStore, renewedAssertion, tokenParameters.getPrincipal(), tokenParameters
            );
            
            response.setToken(token);
            response.setTokenId(renewedAssertion.getId());
            
            DateTime validFrom = null;
            DateTime validTill = null;
            if (renewedAssertion.getSamlVersion().equals(SAMLVersion.VERSION_20)) {
                validFrom = renewedAssertion.getSaml2().getConditions().getNotBefore();
                validTill = renewedAssertion.getSaml2().getConditions().getNotOnOrAfter();
            } else {
                validFrom = renewedAssertion.getSaml1().getConditions().getNotBefore();
                validTill = renewedAssertion.getSaml1().getConditions().getNotOnOrAfter();
            }
            response.setCreated(validFrom.toDate());
            response.setExpires(validTill.toDate());

            LOG.fine("SAML Token successfully renewed");
            return response;
        } catch (Exception ex) {
            LOG.log(Level.WARNING, "", ex);
            throw new STSException("Can't renew SAML assertion", ex, STSException.REQUEST_FAILED);
        }
    }
    
    /**
     * Set the ConditionsProvider
     */
    public void setConditionsProvider(ConditionsProvider conditionsProvider) {
        this.conditionsProvider = conditionsProvider;
    }
    
    /**
     * Get the ConditionsProvider
     */
    public ConditionsProvider getConditionsProvider() {
        return conditionsProvider;
    }

    /**
     * Return whether the provided token will be signed or not. Default is true.
     */
    public boolean isSignToken() {
        return signToken;
    }

    /**
     * Set whether the provided token will be signed or not. Default is true.
     */
    public void setSignToken(boolean signToken) {
        this.signToken = signToken;
    }
    
    /**
     * Set the map of realm->SAMLRealm for this token provider
     * @param realms the map of realm->SAMLRealm for this token provider
     */
    public void setRealmMap(Map<String, SAMLRealm> realms) {
        this.realmMap = realms;
    }
    
    /**
     * Get the map of realm->SAMLRealm for this token provider
     * @return the map of realm->SAMLRealm for this token provider
     */
    public Map<String, SAMLRealm> getRealmMap() {
        return realmMap;
    }
    
    private void validateAssertion(
        SamlAssertionWrapper assertion,
        ReceivedToken tokenToRenew,
        SecurityToken token,
        TokenRenewerParameters tokenParameters
    ) throws WSSecurityException {
        // Check the cached renewal properties
        Properties props = token.getProperties();
        if (props == null) {
            LOG.log(Level.WARNING, "Error in getting properties from cached token");
            throw new STSException(
                "Error in getting properties from cached token", STSException.REQUEST_FAILED
            );
        }
        String isAllowRenewal = (String)props.get(STSConstants.TOKEN_RENEWING_ALLOW);
        String isAllowRenewalAfterExpiry = 
            (String)props.get(STSConstants.TOKEN_RENEWING_ALLOW_AFTER_EXPIRY);
        
        if (isAllowRenewal == null || !Boolean.valueOf(isAllowRenewal)) {
            LOG.log(Level.WARNING, "The token is not allowed to be renewed");
            throw new STSException("The token is not allowed to be renewed", STSException.REQUEST_FAILED);
        }
        
        // Check to see whether the token has expired greater than the configured max expiry time
        if (tokenToRenew.getState() == STATE.EXPIRED) {
            if (!allowRenewalAfterExpiry || isAllowRenewalAfterExpiry == null
                || !Boolean.valueOf(isAllowRenewalAfterExpiry)) {
                LOG.log(Level.WARNING, "Renewal after expiry is not allowed");
                throw new STSException(
                    "Renewal after expiry is not allowed", STSException.REQUEST_FAILED
                );
            }
            DateTime expiryDate = getExpiryDate(assertion);
            DateTime currentDate = new DateTime();
            if ((currentDate.getMillis() - expiryDate.getMillis()) > (maxExpiry * 1000L)) {
                LOG.log(Level.WARNING, "The token expired too long ago to be renewed");
                throw new STSException(
                    "The token expired too long ago to be renewed", STSException.REQUEST_FAILED
                );
            }
        }
        
        // Verify Proof of Possession
        ProofOfPossessionValidator popValidator = new ProofOfPossessionValidator();
        if (verifyProofOfPossession) {
            STSPropertiesMBean stsProperties = tokenParameters.getStsProperties();
            Crypto sigCrypto = stsProperties.getSignatureCrypto();
            CallbackHandler callbackHandler = stsProperties.getCallbackHandler();
            RequestData requestData = new RequestData();
            requestData.setSigVerCrypto(sigCrypto);
            WSSConfig wssConfig = WSSConfig.getNewInstance();
            requestData.setWssConfig(wssConfig);
            requestData.setCallbackHandler(callbackHandler);
            // Parse the HOK subject if it exists
            
            WSDocInfo docInfo = new WSDocInfo(((Element)tokenToRenew.getToken()).getOwnerDocument());
            assertion.parseSubject(
                new WSSSAMLKeyInfoProcessor(requestData, docInfo), sigCrypto, callbackHandler
            );
            
            SAMLKeyInfo keyInfo = assertion.getSubjectKeyInfo();
            if (keyInfo == null) {
                keyInfo = new SAMLKeyInfo((byte[])null);
            }
            if (!popValidator.checkProofOfPossession(tokenParameters, keyInfo)) {
                throw new STSException(
                    "Failed to verify the proof of possession of the key associated with the "
                    + "saml token. No matching key found in the request.",
                    STSException.INVALID_REQUEST
                );
            }
        }
        
        // Check the AppliesTo address
        String appliesToAddress = tokenParameters.getAppliesToAddress();
        if (appliesToAddress != null) {
            if (assertion.getSaml1() != null) {
                List<AudienceRestrictionCondition> restrConditions = 
                    assertion.getSaml1().getConditions().getAudienceRestrictionConditions();
                if (!matchSaml1AudienceRestriction(appliesToAddress, restrConditions)) {
                    LOG.log(Level.WARNING, "The AppliesTo address does not match the Audience Restriction");
                    throw new STSException(
                        "The AppliesTo address does not match the Audience Restriction",
                        STSException.INVALID_REQUEST
                    );
                }
            } else {
                List<AudienceRestriction> audienceRestrs = 
                    assertion.getSaml2().getConditions().getAudienceRestrictions();
                if (!matchSaml2AudienceRestriction(appliesToAddress, audienceRestrs)) {
                    LOG.log(Level.WARNING, "The AppliesTo address does not match the Audience Restriction");
                    throw new STSException(
                        "The AppliesTo address does not match the Audience Restriction",
                        STSException.INVALID_REQUEST
                    );
                }
            }
        }
        
    }
    
    private boolean matchSaml1AudienceRestriction(
        String appliesTo, List<AudienceRestrictionCondition> restrConditions
    ) {
        boolean found = false;
        if (restrConditions != null && !restrConditions.isEmpty()) {
            for (AudienceRestrictionCondition restrCondition : restrConditions) {
                if (restrCondition.getAudiences() != null) {
                    for (Audience audience : restrCondition.getAudiences()) {
                        if (appliesTo.equals(audience.getUri())) {
                            return true;
                        }
                    }
                }
            }
        }
        
        return found;
    }
    
    private boolean matchSaml2AudienceRestriction(
        String appliesTo, List<AudienceRestriction> audienceRestrictions
    ) {
        boolean found = false;
        if (audienceRestrictions != null && !audienceRestrictions.isEmpty()) {
            for (AudienceRestriction audienceRestriction : audienceRestrictions) {
                if (audienceRestriction.getAudiences() != null) {
                    for (org.opensaml.saml2.core.Audience audience : audienceRestriction.getAudiences()) {
                        if (appliesTo.equals(audience.getAudienceURI())) {
                            return true;
                        }
                    }
                }
            }
        }

        return found;
    }
    
    private void signAssertion(
        SamlAssertionWrapper assertion,
        TokenRenewerParameters tokenParameters
    ) throws Exception {
        if (signToken) {
            STSPropertiesMBean stsProperties = tokenParameters.getStsProperties();
            
            // Initialise signature objects with defaults of STSPropertiesMBean
            Crypto signatureCrypto = stsProperties.getSignatureCrypto();
            CallbackHandler callbackHandler = stsProperties.getCallbackHandler();
            SignatureProperties signatureProperties = stsProperties.getSignatureProperties();
            String alias = stsProperties.getSignatureUsername();
            
            String realm = tokenParameters.getRealm();
            SAMLRealm samlRealm = null;
            if (realm != null && realmMap.containsKey(realm)) {
                samlRealm = realmMap.get(realm);
            }
            if (samlRealm != null) {
                // If SignatureCrypto configured in realm then
                // callbackhandler and alias of STSPropertiesMBean is ignored
                if (samlRealm.getSignatureCrypto() != null) {
                    LOG.fine("SAMLRealm signature keystore used");
                    signatureCrypto = samlRealm.getSignatureCrypto();
                    callbackHandler = samlRealm.getCallbackHandler();
                    alias = samlRealm.getSignatureAlias();
                }
                // SignatureProperties can be defined independently of SignatureCrypto
                if (samlRealm.getSignatureProperties() != null) {
                    signatureProperties = samlRealm.getSignatureProperties();
                }
            }
            
            // Get the signature algorithm to use
            String signatureAlgorithm = tokenParameters.getKeyRequirements().getSignatureAlgorithm();
            if (signatureAlgorithm == null) {
                // If none then default to what is configured
                signatureAlgorithm = signatureProperties.getSignatureAlgorithm();
            } else {
                List<String> supportedAlgorithms = 
                    signatureProperties.getAcceptedSignatureAlgorithms();
                if (!supportedAlgorithms.contains(signatureAlgorithm)) {
                    signatureAlgorithm = signatureProperties.getSignatureAlgorithm();
                    LOG.fine("SignatureAlgorithm not supported, defaulting to: " + signatureAlgorithm);
                }
            }
            
            // Get the c14n algorithm to use
            String c14nAlgorithm = tokenParameters.getKeyRequirements().getC14nAlgorithm();
            if (c14nAlgorithm == null) {
                // If none then default to what is configured
                c14nAlgorithm = signatureProperties.getC14nAlgorithm();
            } else {
                List<String> supportedAlgorithms = 
                    signatureProperties.getAcceptedC14nAlgorithms();
                if (!supportedAlgorithms.contains(c14nAlgorithm)) {
                    c14nAlgorithm = signatureProperties.getC14nAlgorithm();
                    LOG.fine("C14nAlgorithm not supported, defaulting to: " + c14nAlgorithm);
                }
            }
            
            // If alias not defined, get the default of the SignatureCrypto
            if ((alias == null || "".equals(alias)) && (signatureCrypto != null)) {
                alias = signatureCrypto.getDefaultX509Identifier();
                LOG.fine("Signature alias is null so using default alias: " + alias);
            }
            // Get the password
            WSPasswordCallback[] cb = {new WSPasswordCallback(alias, WSPasswordCallback.SIGNATURE)};
            LOG.fine("Creating SAML Token");
            callbackHandler.handle(cb);
            String password = cb[0].getPassword();
    
            LOG.fine("Signing SAML Token");
            boolean useKeyValue = signatureProperties.isUseKeyValue();
            assertion.signAssertion(
                alias, password, signatureCrypto, useKeyValue, c14nAlgorithm, signatureAlgorithm
            );
        } else {
            if (assertion.getSaml1().getSignature() != null) {
                assertion.getSaml1().setSignature(null);
            } else if (assertion.getSaml2().getSignature() != null) {
                assertion.getSaml2().setSignature(null);
            } 
        }
        
    }
    
    private void createNewConditions(SamlAssertionWrapper assertion, TokenRenewerParameters tokenParameters) {
        ConditionsBean conditions = 
            conditionsProvider.getConditions(convertToProviderParameters(tokenParameters));
        
        if (assertion.getSaml1() != null) {
            org.opensaml.saml1.core.Assertion saml1Assertion = assertion.getSaml1();
            saml1Assertion.setIssueInstant(new DateTime());
            
            org.opensaml.saml1.core.Conditions saml1Conditions =
                SAML1ComponentBuilder.createSamlv1Conditions(conditions);
            
            saml1Assertion.setConditions(saml1Conditions);
        } else {
            org.opensaml.saml2.core.Assertion saml2Assertion = assertion.getSaml2();
            saml2Assertion.setIssueInstant(new DateTime());
            
            org.opensaml.saml2.core.Conditions saml2Conditions =
                SAML2ComponentBuilder.createConditions(conditions);
            
            saml2Assertion.setConditions(saml2Conditions);
        }
    }
    
    private TokenProviderParameters convertToProviderParameters(
        TokenRenewerParameters renewerParameters
    ) {
        TokenProviderParameters providerParameters = new TokenProviderParameters();
        providerParameters.setAppliesToAddress(renewerParameters.getAppliesToAddress());
        providerParameters.setEncryptionProperties(renewerParameters.getEncryptionProperties());
        providerParameters.setKeyRequirements(renewerParameters.getKeyRequirements());
        providerParameters.setPrincipal(renewerParameters.getPrincipal());
        providerParameters.setRealm(renewerParameters.getRealm());
        providerParameters.setStsProperties(renewerParameters.getStsProperties());
        providerParameters.setTokenRequirements(renewerParameters.getTokenRequirements());
        providerParameters.setTokenStore(renewerParameters.getTokenStore());
        providerParameters.setWebServiceContext(renewerParameters.getWebServiceContext());
        
        // Store token to renew in the additional properties in case you want to base some
        // Conditions on the token
        Map<String, Object> additionalProperties = renewerParameters.getAdditionalProperties();
        if (additionalProperties == null) {
            additionalProperties = new HashMap<String, Object>();
        }
        additionalProperties.put(ReceivedToken.class.getName(), renewerParameters.getToken());
        providerParameters.setAdditionalProperties(additionalProperties);
        
        return providerParameters;
    }
    
    private String createNewId(SamlAssertionWrapper assertion) {
        if (assertion.getSaml1() != null) {
            org.opensaml.saml1.core.Assertion saml1Assertion = assertion.getSaml1();
            String oldId = saml1Assertion.getID();
            saml1Assertion.setID(IDGenerator.generateID("_"));
            
            return oldId;
        } else {
            org.opensaml.saml2.core.Assertion saml2Assertion = assertion.getSaml2();
            String oldId = saml2Assertion.getID();
            saml2Assertion.setID(IDGenerator.generateID("_"));
            
            return oldId;
        }
    }
    
    private void storeTokenInCache(
        TokenStore tokenStore, 
        SamlAssertionWrapper assertion, 
        Principal principal,
        TokenRenewerParameters tokenParameters
    ) throws WSSecurityException {
        // Store the successfully renewed token in the cache
        byte[] signatureValue = assertion.getSignatureValue();
        if (tokenStore != null && signatureValue != null && signatureValue.length > 0) {
            DateTime validTill = null;
            if (assertion.getSamlVersion().equals(SAMLVersion.VERSION_20)) {
                validTill = assertion.getSaml2().getConditions().getNotOnOrAfter();
            } else {
                validTill = assertion.getSaml1().getConditions().getNotOnOrAfter();
            }

            SecurityToken securityToken = 
                CacheUtils.createSecurityTokenForStorage(assertion.getElement(), assertion.getId(), 
                    validTill.toDate(), tokenParameters.getPrincipal(), tokenParameters.getRealm(),
                    tokenParameters.getTokenRequirements().getRenewing());
            CacheUtils.storeTokenInCache(
                securityToken, tokenParameters.getTokenStore(), signatureValue);
        }
    }

    
    private DateTime getExpiryDate(SamlAssertionWrapper assertion) {
        if (assertion.getSamlVersion().equals(SAMLVersion.VERSION_20)) {
            return assertion.getSaml2().getConditions().getNotOnOrAfter();
        } else {
            return assertion.getSaml1().getConditions().getNotOnOrAfter();
        }
    }

    private static class ProofOfPossessionValidator extends AbstractSamlPolicyValidator {
        
        public boolean checkProofOfPossession(
            TokenRenewerParameters tokenParameters,
            SAMLKeyInfo subjectKeyInfo
        ) {
            MessageContext messageContext = tokenParameters.getWebServiceContext().getMessageContext();
            final List<WSHandlerResult> handlerResults = 
                CastUtils.cast((List<?>) messageContext.get(WSHandlerConstants.RECV_RESULTS));

            List<WSSecurityEngineResult> signedResults = new ArrayList<WSSecurityEngineResult>();
            if (handlerResults != null && handlerResults.size() > 0) {
                WSHandlerResult handlerResult = handlerResults.get(0);
                List<WSSecurityEngineResult> results = handlerResult.getResults();
                final List<Integer> signedActions = new ArrayList<Integer>(2);
                signedActions.add(WSConstants.SIGN);
                signedActions.add(WSConstants.UT_SIGN);
                
                signedResults.addAll(WSSecurityUtil.fetchAllActionResults(results, signedActions));
            }
            
            TLSSessionInfo tlsInfo = (TLSSessionInfo)messageContext.get(TLSSessionInfo.class.getName());
            Certificate[] tlsCerts = null;
            if (tlsInfo != null) {
                tlsCerts = tlsInfo.getPeerCertificates();
            }
            
            return compareCredentials(subjectKeyInfo, signedResults, tlsCerts);
        }
    }
}
