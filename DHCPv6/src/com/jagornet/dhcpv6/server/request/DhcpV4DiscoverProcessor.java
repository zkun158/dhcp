/*
 * Copyright 2009 Jagornet Technologies, LLC.  All Rights Reserved.
 *
 * This software is the proprietary information of Jagornet Technologies, LLC. 
 * Use is subject to license terms.
 *
 */

/*
 *   This file DhcpV4DiscoverProcessor.java is part of DHCPv6.
 *
 *   DHCPv6 is free software: you can redistribute it and/or modify
 *   it under the terms of the GNU General Public License as published by
 *   the Free Software Foundation, either version 3 of the License, or
 *   (at your option) any later version.
 *
 *   DHCPv6 is distributed in the hope that it will be useful,
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *   GNU General Public License for more details.
 *
 *   You should have received a copy of the GNU General Public License
 *   along with DHCPv6.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.jagornet.dhcpv6.server.request;

import java.net.InetAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jagornet.dhcpv6.db.IdentityAssoc;
import com.jagornet.dhcpv6.message.DhcpV4Message;
import com.jagornet.dhcpv6.server.config.DhcpServerPolicies;
import com.jagornet.dhcpv6.server.config.DhcpServerPolicies.Property;
import com.jagornet.dhcpv6.server.request.binding.Binding;
import com.jagornet.dhcpv6.server.request.binding.V4AddrBindingManager;
import com.jagornet.dhcpv6.util.DhcpConstants;
import com.jagornet.dhcpv6.util.Util;
import com.jagornet.dhcpv6.xml.Link;

/**
 * Title: DhcpV4DiscoverProcessor
 * Description: The main class for processing V4 DISCOVER messages.
 * 
 * @author A. Gregory Rabil
 */

public class DhcpV4DiscoverProcessor extends BaseDhcpV4Processor
{
	
	/** The log. */
	private static Logger log = LoggerFactory.getLogger(DhcpV4DiscoverProcessor.class);
    
    /**
     * Construct an DhcpV4DiscoverProcessor processor.
     * 
     * @param requestMsg the Discover message
     * @param clientLinkAddress the client link address
     */
    public DhcpV4DiscoverProcessor(DhcpV4Message requestMsg, InetAddress clientLinkAddress)
    {
        super(requestMsg, clientLinkAddress);
    }

    /* (non-Javadoc)
     * @see com.jagornet.dhcpv6.server.request.BaseDhcpProcessor#preProcess()
     */
    @Override
    public boolean preProcess()
    {
    	if (!super.preProcess()) {
    		return false;
    	}
    	
    	InetAddress ciAddr = requestMsg.getCiAddr();
    	if (!ciAddr.equals(DhcpConstants.ZEROADDR)) {
    		log.warn("Ignoring Discover message: " +
    				"ciAddr field is non-zero: " +
    				ciAddr);
    		return false;
    	}
    	
    	byte chAddr[] = requestMsg.getChAddr();
    	if (isIgnoredMac(chAddr)) {
    		log.warn("Ignorning Discover message: " +
    				"mac=" + Util.toHexString(chAddr));
    	}
    	
    	if (requestMsg.getDhcpV4ServerIdOption() != null) {
    		log.warn("Ignoring Discover message: " +
					 "ServerId option is not null");
    		return false;
    	}
        
    	return true;
    }
    
    protected boolean isIgnoredMac(byte[] chAddr)
    {
    	String ignoredMacPolicy = DhcpServerPolicies.globalPolicy(Property.V4_IGNORED_MACS);
    	if (ignoredMacPolicy != null) {
    		String[] ignoredMacs = ignoredMacPolicy.split(",");
    		if (ignoredMacs != null) {
    			for (String ignoredMac : ignoredMacs) {
					if (ignoredMac.trim().equalsIgnoreCase(Util.toHexString(chAddr))) {
						return true;
					}
				}
    		}
    	}
    	return false;
    }

    /* (non-Javadoc)
     * @see com.jagornet.dhcpv6.server.request.BaseDhcpProcessor#process()
     */
    @Override
    public boolean process()
    {
		boolean sendReply = true;
		boolean rapidCommit = isRapidCommit(requestMsg, clientLink.getLink());
		byte chAddr[] = requestMsg.getChAddr();
		
		V4AddrBindingManager bindingMgr = dhcpServerConfig.getV4AddrBindingMgr();
		if (bindingMgr != null) {
			log.info("Processing Discover from: " + Util.toHexString(chAddr));
			Binding binding = bindingMgr.findCurrentBinding(clientLink.getLink(), 
					chAddr, requestMsg);
			if (binding == null) {
				// no current binding for this MAC, create a new one
				binding = bindingMgr.createDiscoverBinding(clientLink.getLink(), 
						chAddr, requestMsg, rapidCommit);
			}
			else {
				binding = bindingMgr.updateBinding(binding, clientLink.getLink(), 
						chAddr, requestMsg, rapidCommit ? 
								IdentityAssoc.COMMITTED : IdentityAssoc.ADVERTISED);
			}
			if (binding != null) {
				// have a good binding, put it in the reply with options
				addBindingToReply(clientLink, binding);
				bindings.add(binding);
			}
			else {
				// something went wrong, report NoAddrsAvail status for IA_NA
// TAHI tests want this status at the message level
//	    				addIaNaOptionStatusToReply(dhcpIaNaOption, 
//	    						DhcpConstants.STATUS_CODE_NOADDRSAVAIL);
//    			setReplyStatus(DhcpConstants.STATUS_CODE_NOADDRSAVAIL);
				log.error("Failed to create binding for Discover from: " +
						Util.toHexString(chAddr));
			}
		}
		else {
			log.error("Unable to process V4 Discover:" +
					" No V4AddrBindingManager available");
		}
    	
    	if (sendReply) {
    		if (rapidCommit) {
    			replyMsg.setMessageType((short)DhcpConstants.V4MESSAGE_TYPE_ACK);
    		}
    		else {
    	        replyMsg.setMessageType((short)DhcpConstants.V4MESSAGE_TYPE_OFFER);
    		}
    		if (!bindings.isEmpty()) {
    			populateReplyMsgOptions(clientLink);
// TODO
//    			processDdnsUpdates();
    		}
    	}    	
		return sendReply;
    }
	
	/**
	 * Checks if is rapid commit.
	 * 
	 * @param requestMsg the request msg
	 * @param clientLink the client link
	 * 
	 * @return true, if is rapid commit
	 */
	private boolean isRapidCommit(DhcpV4Message requestMsg, Link clientLink)
	{
		if (requestMsg.hasOption(DhcpConstants.OPTION_RAPID_COMMIT) && 
				DhcpServerPolicies.effectivePolicyAsBoolean(requestMsg, clientLink, 
						Property.SUPPORT_RAPID_COMMIT)) {
			return true;
		}
		return false;
	}
}