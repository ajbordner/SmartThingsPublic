/**
 *  Connect Events Web Service
 *
 *  Copyright 2015 linkAges Connect
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
    name: "Connect Events Web Service",
    namespace: "ajbordner",
    author: "linkAges Connect",
    description: "Web Service for downloading the sensor event history for Connect users",
    category: "Health & Wellness",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    oauth: [displayName: "Palo Alto Medical Foundation", displayLink: "http://connect.linkages.org"])

preferences {
  section ("Allow linkAges Connect to access these things...") {
    input "switches", "capability.powerMeter", title: "Outlet", multiple: true, required: true
    input "acceleration", "capability.accelerationSensor", title: "Accelerometer", multiple: true, required: true
    input "contact", "capability.contactSensor", title: "Contact sensor", multiple: true, required: true
    input "motion", "capability.motionSensor", title: "Motion sensor", multiple: true, required: true
  }
}

mappings {
   path("/getHistory") {
    action: [
      GET: "listEvents"
    ]
  }
   path("/setPowerCutoff") {
    action: [
      GET: "setPowerCutoff"
    ]
  }
   path("/getPowerCutoff") {
    action: [
     GET: "getPowerCutoff"
    ]
  }
   path("/pushEvents") {   // manually push event data
    action: [
     GET: "postEventsHandler"
    ]
  }
}

def listEvents() {
	def maxNumEvents = 99999
    def startDate = new Date().parse("yyyy-MM-dd", params?.startDate)
    def endDate = new Date().parse("yyyy-MM-dd", params?.endDate)
 	def allEvents = contact.eventsBetween(startDate, endDate, [max: maxNumEvents]) + motion.eventsBetween(startDate, endDate, [max: maxNumEvents]) + switches.eventsBetween(startDate, endDate, [max: maxNumEvents])
   	def resp = []
    allEvents.each { dev ->
    	dev.each {
    		resp << [label: it.displayName, name: it.name, value: it.value, date: it.isoDate]
        }
    }
    return resp
}

def setPowerCutoff() {
	state.powerCutoff = Float.parseFloat(params?.watts)
    return [watts: state.powerCutoff]
}

def getPowerCutoff() {
	return [watts: state.powerCutoff]
}

def installed() {
	log.debug "Installed with settings: ${settings}"

	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"

	unsubscribe()
	initialize()
}

def initialize() {
	subscribe(switches, "power", switchHandler)
    subscribe(acceleration, "acceleration", accelerationHandler)
    subscribe(contact, "contact", contactHandler)
    subscribe(motion, "motion", motionHandler)
    state.evtData = []
    state.rawEvtData = []
    state.powerOn = null
    state.powerChangeTime = (new Date()).time
    state.powerCutoff = 1.0
    state.motionActive = null
    state.motionChangeTime = (new Date()).time
    state.motionDelay = 60.0
    schedule("0 * * * * ?",postEventsHandler)
}

def addRawEvent(evt) {
	state.rawEvtData << [name: evt.device.name, label : evt.device.label, event_type: evt.name, value: evt.value, date: evt.isoDate, location: location.name, hub_id: evt.hubId]
}

def addEvent(evt) {
log.debug "addEvent: ${evt.device.name}, ${evt.name}, ${evt.value}"
	state.evtData << [name: evt.device.name, label : evt.device.label, event_type: evt.name, value: evt.value, date: evt.isoDate, duration: null, location: location.name, hub_id: evt.hubId]
}

def addOutletEvent(evt, eventValue, duration) {
  state.powerChangeTime = (new Date()).time
  def intDuration = duration.setScale(0, BigDecimal.ROUND_HALF_UP)
  state.evtData << [name: evt.device.name, label : evt.device.label, event_type: evt.name, value: eventValue, date: evt.isoDate, duration: intDuration, location: location.name, hub_id: evt.hubId]
}

def checkForMotion() {
	// Only change motion sensor activity if persists for longer than state.motionDelay seconds
    def now = (new Date()).time
    def latestMotionState = motion.latestState("motion");
    def currentMotionState = motion.currentState("motion");
    def latestTime = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",latestMotionState.dateCreated[0])
    def timeSinceStateChange = (now - latestTime.time)/1000.0   // time since last state change in seconds
    //log.debug "TEST: ${state.motionActive}, ${currentMotionState.value[0]}, ${timeSinceStateChange}"
    if (state.motionActive == null) {
    	state.motionActive = (currentMotionState.value[0] == "active")
    } else {
    	if (state.motionActive && (currentMotionState.value[0] == "inactive") && (timeSinceStateChange >= state.motionDelay)) {
        	state.motionActive = false
            def duration = (now - state.motionChangeTime)/1000.0 - timeSinceStateChange
            state.motionChangeTime = now
            //log.debug "MOTION INACTIVE ${duration.setScale(0, BigDecimal.ROUND_HALF_UP)}"
    		state.evtData << [name: motion.name[0], label: motion.label[0], event_type: "motion", value: "inactive", date: latestMotionState.dateCreated[0], duration: duration.setScale(0, BigDecimal.ROUND_HALF_UP), location: location.name, hub_id: motion.hub.id[0]]
    	} else if (!state.motionActive && (currentMotionState.value[0] == "active")) {
        	state.motionActive = true
            def duration = (now - state.motionChangeTime)/1000.0            
            state.motionChangeTime = now
            //log.debug "MOTION ACTIVE ${duration.setScale(0, BigDecimal.ROUND_HALF_UP)}"
    		state.evtData << [name: motion.name[0], label: motion.label[0], event_type: "motion", value: "active", date: latestMotionState.dateCreated[0], duration: duration.setScale(0, BigDecimal.ROUND_HALF_UP), location: location.name, hub_id: motion.hub.id[0]]
    	}
    }
}

def postEventsHandler() {
	checkForMotion()
	// Push if any processed event data
   	if (state.evtData.size() > 0) {
		def json = new groovy.json.JsonBuilder(["processed": state.evtData, "raw": state.rawEvtData]).toString()
    	//log.debug("JSON: ${json}")
    	def params = [
    		uri: "https://pamf-connect-propane.linkages.org/smartthings.php",
    	    body: json
   		]
    	try {
        	httpPostJson(params)
    	} catch ( groovyx.net.http.HttpResponseException ex ) {
       		log.debug "Unexpected response error: ${ex.statusCode}"
    	}
        state.evtData = []   // clear processed event data
        state.rawEvtData = []  // clear raw event data
     }
}

def switchHandler(evt) {
	if (evt.name == "power") {
    	def watts = Float.parseFloat(evt.value)
    	if (state.powerOn == null) {
        	state.powerOn = (watts >= state.powerCutoff)
        } else {
        	def now = new Date()
            def duration = (now.time - state.powerChangeTime)/1000.0  // time since last state change in seconds
 			if (state.powerOn && (watts < state.powerCutoff)) {
    			state.powerOn = false
            	addOutletEvent(evt, "inactive",duration)
  			} else if (!state.powerOn && (watts >= state.powerCutoff)) {
        		state.powerOn = true
            	addOutletEvent(evt, "active",duration)
      		}
       }
    }
    addRawEvent(evt)
}

def accelerationHandler(evt) {
	addEvent(evt)
    addRawEvent(evt)
}

def contactHandler(evt) {
	addEvent(evt)
    addRawEvent(evt)
}

def motionHandler(evt) {
	checkForMotion()
    addRawEvent(evt)
}
