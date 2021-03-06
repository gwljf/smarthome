/**
 * Copyright (c) 2014-2015 openHAB UG (haftungsbeschraenkt) and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package org.eclipse.smarthome.core.thing.internal

import static org.hamcrest.CoreMatchers.*
import static org.junit.Assert.*
import static org.junit.matchers.JUnitMatchers.*

import org.eclipse.smarthome.core.common.registry.RegistryChangeListener
import org.eclipse.smarthome.core.events.EventPublisher
import org.eclipse.smarthome.core.library.types.DecimalType
import org.eclipse.smarthome.core.library.types.StringType
import org.eclipse.smarthome.core.thing.Bridge;
import org.eclipse.smarthome.core.thing.Channel
import org.eclipse.smarthome.core.thing.ChannelUID
import org.eclipse.smarthome.core.thing.ManagedThingProvider
import org.eclipse.smarthome.core.thing.Thing
import org.eclipse.smarthome.core.thing.ThingRegistry
import org.eclipse.smarthome.core.thing.ThingStatus
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID
import org.eclipse.smarthome.core.thing.ThingUID
import org.eclipse.smarthome.core.thing.binding.ThingHandler
import org.eclipse.smarthome.core.thing.binding.ThingHandlerCallback
import org.eclipse.smarthome.core.thing.binding.ThingHandlerFactory
import org.eclipse.smarthome.core.thing.binding.builder.BridgeBuilder;
import org.eclipse.smarthome.core.thing.binding.builder.ThingStatusInfoBuilder;
import org.eclipse.smarthome.core.thing.binding.builder.ThingBuilder
import org.eclipse.smarthome.core.thing.link.ItemChannelLink
import org.eclipse.smarthome.core.thing.link.ManagedItemChannelLinkProvider
import org.eclipse.smarthome.core.types.State
import org.eclipse.smarthome.test.OSGiTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.osgi.service.event.Event
import org.osgi.service.event.EventConstants
import org.osgi.service.event.EventHandler

class ThingManagerOSGiTest extends OSGiTest {

	ManagedThingProvider managedThingProvider

    ManagedItemChannelLinkProvider managedItemChannelLinkProvider
    
    def THING_TYPE_UID = new ThingTypeUID("binding:type")
    
    def THING_UID = new ThingUID(THING_TYPE_UID, "id")
    
    def CHANNEL_UID = new ChannelUID(THING_UID, "channel")
    
	Thing THING = ThingBuilder.create(THING_UID).withChannels([new Channel(CHANNEL_UID, "Switch")]).build()

    EventPublisher eventPublisher
    
	@Before
	void setUp() {
		registerVolatileStorageService()
        managedItemChannelLinkProvider = getService(ManagedItemChannelLinkProvider)
		managedThingProvider = getService(ManagedThingProvider)
        eventPublisher = getService(EventPublisher)
	}
	
	@After
	void teardown() {
		managedThingProvider.getAll().each {
			managedThingProvider.remove(it.getUID())
		}
	}

	@Test
	void 'ThingManager calls registerHandler for added Thing'() {

		def registerHandlerCalled = false

		def thingHandlerFactory = [
			supportsThingType: {ThingTypeUID thingTypeUID -> true},
			registerHandler: {thing, callback -> registerHandlerCalled = true}
		] as ThingHandlerFactory

		registerService(thingHandlerFactory)

		managedThingProvider.add(THING)

		waitForAssert {assertThat registerHandlerCalled, is(true)}
	}

	@Test
	void 'ThingManager calls unregisterHandler for removed Thing'() {

		def unregisterHandlerCalled = false
        def removeThingCalled = false

		def thingHandlerFactory = [
			supportsThingType: {ThingTypeUID thingTypeUID -> true},
			registerHandler: { Thing thing, ThingHandlerCallback callback ->
				def thingHandler = {setCallback: {}} as ThingHandler
				registerService(thingHandler,[
					(ThingHandler.SERVICE_PROPERTY_THING_ID): THING.getUID(),
					(ThingHandler.SERVICE_PROPERTY_THING_TYPE): THING.getThingTypeUID()
				] as Hashtable)
			},
			unregisterHandler: {Thing thing -> unregisterHandlerCalled = true},
            removeThing: { ThingUID thingUID -> removeThingCalled = true}
		] as ThingHandlerFactory

		registerService(thingHandlerFactory)

		managedThingProvider.add(THING)

		managedThingProvider.remove(THING.getUID())

        waitForAssert {assertThat removeThingCalled, is(true)}
		waitForAssert {assertThat unregisterHandlerCalled, is(true)}
	}    

    @Test
    void 'ThingManager does not delegate update events to its source'() {

        def itemName = "name"
        def handleUpdateWasCalled = false
        
        managedThingProvider.add(THING)
        managedItemChannelLinkProvider.add(new ItemChannelLink(itemName, CHANNEL_UID))
        def thingHandler = [
            handleUpdate: { ChannelUID channelUID, State newState ->
                handleUpdateWasCalled = true
            },
            setCallback: {}
        ] as ThingHandler
        
        registerService(thingHandler,[
            (ThingHandler.SERVICE_PROPERTY_THING_ID): THING.getUID(),
            (ThingHandler.SERVICE_PROPERTY_THING_TYPE): THING.getThingTypeUID()
        ] as Hashtable)
        
        // event should be delivered
        eventPublisher.postUpdate(itemName, new DecimalType(10))
        waitForAssert { assertThat handleUpdateWasCalled, is(true) }
       
        handleUpdateWasCalled = false
        
        // event should not be delivered, because the source is the same
        eventPublisher.postUpdate(itemName, new DecimalType(10), CHANNEL_UID.toString())
        waitFor {handleUpdateWasCalled == true}
        assertThat handleUpdateWasCalled, is(false) 
    }
    
    @Test
    void 'ThingManager handles state updates correctly'() {

        def itemName = "name"
        def thingUpdatedWasCalled = false
        def callback;
        
        managedThingProvider.add(THING)
        managedItemChannelLinkProvider.add(new ItemChannelLink(itemName, CHANNEL_UID))
        def thingHandler = [
            thingUpdated: {
                thingUpdatedWasCalled = true
            },
            setCallback: {callbackArg -> 
                callback = callbackArg 
            }
        ] as ThingHandler
        
        registerService(thingHandler,[
            (ThingHandler.SERVICE_PROPERTY_THING_ID): THING.getUID(),
            (ThingHandler.SERVICE_PROPERTY_THING_TYPE): THING.getThingTypeUID()
        ] as Hashtable)
        
        Event event = null
        registerService([ 
            handleEvent: { Event e ->
                event = e;
        }] as EventHandler,[
            (EventConstants.EVENT_TOPIC): 'smarthome/update/*'
        ] as Hashtable)
        
        // thing manager registered a listener, that delegates the update to the OSGi event bus
        callback.stateUpdated(CHANNEL_UID, new StringType("Value"))
        waitForAssert { assertThat event, is(not(null)) }
        waitForAssert { assertThat event.getProperty("state"), is(equalTo("Value")) }
        
        event = null
        def thing = ThingBuilder.create(THING_UID).withChannels([new Channel(CHANNEL_UID, "Switch")]).build()
        managedThingProvider.update(thing)
        
        callback.stateUpdated(CHANNEL_UID, new StringType("Value"))
        waitForAssert { assertThat event, is(not(null)) }
        waitForAssert { assertThat event.getProperty("state"), is(equalTo("Value")) }
        waitForAssert { assertThat thingUpdatedWasCalled, is(true) }
    }
    
    @Test
    void 'ThingManager handles post command correctly'() {

        def itemName = "name"
        def callback;
        
        managedThingProvider.add(THING)
        managedItemChannelLinkProvider.add(new ItemChannelLink(itemName, CHANNEL_UID))
        def thingHandler = [
            setCallback: {callbackArg ->
                callback = callbackArg
            }
        ] as ThingHandler
        
        registerService(thingHandler,[
            (ThingHandler.SERVICE_PROPERTY_THING_ID): THING.getUID(),
            (ThingHandler.SERVICE_PROPERTY_THING_TYPE): THING.getThingTypeUID()
        ] as Hashtable)
        
        Event event = null
        registerService([
            handleEvent: { Event e ->
                event = e;
        }] as EventHandler,[
            (EventConstants.EVENT_TOPIC): 'smarthome/command/*'
        ] as Hashtable)
        
        // thing manager registered a listener, that delegates the command to the OSGi event bus
        callback.postCommand(CHANNEL_UID, new StringType("Value"))
        waitForAssert { assertThat event, is(not(null)) }
        waitForAssert { assertThat event.getProperty("command"), is(equalTo("Value")) }
    }

    @Test
    void 'ThingManager handles thing status updates "online" and "offline" correctly'() {
        ThingHandlerCallback callback;
        
        managedThingProvider.add(THING)
        def thingHandler = [
            setCallback: {callbackArg ->
                callback = callbackArg
            }
        ] as ThingHandler
        
        registerService(thingHandler,[
            (ThingHandler.SERVICE_PROPERTY_THING_ID): THING.getUID(),
            (ThingHandler.SERVICE_PROPERTY_THING_TYPE): THING.getThingTypeUID()
        ] as Hashtable)
		
        def statusInfo = ThingStatusInfoBuilder.create(ThingStatus.ONLINE, ThingStatusDetail.NONE).build()
        callback.statusUpdated(THING, statusInfo)
        assertThat THING.statusInfo, is(statusInfo)
        
        statusInfo = ThingStatusInfoBuilder.create(ThingStatus.OFFLINE, ThingStatusDetail.NONE).build()
        callback.statusUpdated(THING, statusInfo)
        assertThat THING.statusInfo, is(statusInfo)
    }
    
    @Test
    void 'ThingManager handles thing status updates "uninitialized" and "initializing" correctly'() {
        def thingHandler = [
            setCallback: {
            }
        ] as ThingHandler

        def thingHandlerFactory = [
            supportsThingType: {ThingTypeUID thingTypeUID -> true},
            registerHandler: {thing, callback ->
                registerService(thingHandler,[
                    (ThingHandler.SERVICE_PROPERTY_THING_ID): THING.getUID(),
                    (ThingHandler.SERVICE_PROPERTY_THING_TYPE): THING.getThingTypeUID()
                ] as Hashtable)}
        ] as ThingHandlerFactory

        registerService(thingHandlerFactory)

        def statusInfo = ThingStatusInfoBuilder.create(ThingStatus.UNINITIALIZED, ThingStatusDetail.NONE).build()
        assertThat THING.statusInfo, is(statusInfo)
        
        managedThingProvider.add(THING)
        statusInfo = ThingStatusInfoBuilder.create(ThingStatus.INITIALIZING, ThingStatusDetail.NONE).build()
        assertThat THING.statusInfo, is(statusInfo)
        
        unregisterService(THING.getHandler())
        statusInfo = ThingStatusInfoBuilder.create(ThingStatus.UNINITIALIZED, ThingStatusDetail.HANDLER_MISSING_ERROR).build()
        assertThat THING.statusInfo, is(statusInfo)
    }
    
    @Test
    void 'ThingManager handles thing status update "uninitialized" with an exception correctly'() {
        def exceptionMsg = "Some runtime exception occurred!"
        
        def thingHandler = [
            setCallback: {
            }
        ] as ThingHandler

        def thingHandlerFactory = [
            supportsThingType: {ThingTypeUID thingTypeUID -> true},
            registerHandler: {thing, callback ->
                    throw new RuntimeException(exceptionMsg)
                }
        ] as ThingHandlerFactory

        registerService(thingHandlerFactory)

        def statusInfo = ThingStatusInfoBuilder.create(ThingStatus.UNINITIALIZED,
                ThingStatusDetail.HANDLER_INITIALIZING_ERROR).withDescription(exceptionMsg).build()
        managedThingProvider.add(THING)
        assertThat THING.statusInfo, is(statusInfo)
    }

    @Test
    void 'ThingManager handles bridge status updates "online" and "offline" correctly'() {
        Bridge bridge = BridgeBuilder.create(new ThingUID(THING_TYPE_UID, "bridge-id")).build()
        Thing thingA = ThingBuilder.create(new ThingUID(THING_TYPE_UID, "thing-a-id")).withBridge(bridge.getUID())build()
        Thing thingB = ThingBuilder.create(new ThingUID(THING_TYPE_UID, "thing-b-id")).withBridge(bridge.getUID()).build()
        
        ThingHandlerCallback callback;
        
        managedThingProvider.add(bridge)
        managedThingProvider.add(thingA)
        managedThingProvider.add(thingB)
        
        def bridgeHandler = [
            setCallback: {callbackArg ->
                callback = callbackArg
            }
        ] as ThingHandler
        
        registerService(bridgeHandler,[
            (ThingHandler.SERVICE_PROPERTY_THING_ID): bridge.getUID(),
            (ThingHandler.SERVICE_PROPERTY_THING_TYPE): bridge.getThingTypeUID()
        ] as Hashtable)

        def bridgeStatusInfo = ThingStatusInfoBuilder.create(ThingStatus.OFFLINE).build()
        callback.statusUpdated(bridge, bridgeStatusInfo)
        assertThat bridge.statusInfo, is(bridgeStatusInfo)

        def thingStatusInfo = ThingStatusInfoBuilder.create(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE).build()
        for(Thing bridgeThing : bridge.getThings()) {
            assertThat bridgeThing.statusInfo, is(thingStatusInfo)
        }
        
        bridgeStatusInfo = ThingStatusInfoBuilder.create(ThingStatus.ONLINE, ThingStatusDetail.NONE).build()
        callback.statusUpdated(bridge, bridgeStatusInfo)
        assertThat bridge.statusInfo, is(bridgeStatusInfo)
        
        thingStatusInfo = ThingStatusInfoBuilder.create(ThingStatus.ONLINE, ThingStatusDetail.NONE).build()
        for(Thing bridgeThing : bridge.getThings()) {
            assertThat bridgeThing.statusInfo, is(thingStatusInfo)
        }
    }

    @Test
    void 'ThingManager handles thing updates correctly'() {

        def itemName = "name"
        ThingHandlerCallback callback;
        
        managedThingProvider.add(THING)
        managedItemChannelLinkProvider.add(new ItemChannelLink(itemName, CHANNEL_UID))
        def thingHandler = [
            setCallback: {callbackArg ->
                callback = callbackArg
            },
            thingUpdated: {}
        ] as ThingHandler
        
        registerService(thingHandler,[
            (ThingHandler.SERVICE_PROPERTY_THING_ID): THING.getUID(),
            (ThingHandler.SERVICE_PROPERTY_THING_TYPE): THING.getThingTypeUID()
        ] as Hashtable)
        
        boolean thingUpdated = false
        
        ThingRegistry thingRegistry = getService(ThingRegistry)
        def registryChangeListener = [ updated: {old, updated -> thingUpdated = true} ] as RegistryChangeListener
        
        try {
            thingRegistry.addRegistryChangeListener(registryChangeListener)
            callback.thingUpdated(THING)
            assertThat thingUpdated, is(true)
        } finally {
            thingRegistry.removeRegistryChangeListener(registryChangeListener)
        }
    }
}
