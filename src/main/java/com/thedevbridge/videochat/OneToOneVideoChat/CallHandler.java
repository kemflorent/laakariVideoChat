package com.thedevbridge.videochat.OneToOneVideoChat;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

import org.kurento.client.EventListener;
import org.kurento.client.IceCandidate;
import org.kurento.client.IceCandidateFoundEvent;
import org.kurento.client.KurentoClient;
import org.kurento.jsonrpc.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

public class CallHandler extends TextWebSocketHandler {
	
	private static final Logger logger = LoggerFactory.getLogger(CallHandler.class);
	private static final Gson gson = new GsonBuilder().create();
	
	private final ConcurrentHashMap<String, CallMediaPipeline> pipelines = new ConcurrentHashMap<>();
	
	@Autowired
	private KurentoClient kurento;
	@Autowired
	private UserRegistry registry;
	
	@Override
	public void afterConnectionClosed(WebSocketSession session,
			CloseStatus status) throws Exception {
		// TODO Auto-generated method stub
		//super.afterConnectionClosed(session, status);
		stop(session);
		registry.removeBySession(session);
	}

	@Override
	protected void handleTextMessage(WebSocketSession session,
			TextMessage message) throws Exception {
	
		
		JsonObject jsonMessage = gson.fromJson(message.getPayload(), JsonObject.class);
		UserSession user = registry.getBySession(session);
		
		if(user !=null){
			logger.debug("incomming message from user '{}':{}", user.getName(), jsonMessage);
		}else{
			logger.debug("incomming message from new user:{}", jsonMessage);
		}
		switch(jsonMessage.get("id").getAsString()){
			case "register":{
				try{
					register(session,jsonMessage);
				}catch(Throwable t){
					handleErrorResponse(t,session,"registerResponse");
				}
				break;
			}
			case "call":{
				try{
					call(user,jsonMessage);
				}catch(Throwable t){
					handleErrorResponse(t,session,"callResponse");
				}
				break;
			}
			case "incomingCallResponse":{
				incomingCallResponse(user,jsonMessage);
				break;
			}
			case "onIceCandidate":{
				JsonObject candidate = jsonMessage.get("candidate").getAsJsonObject();
				if(user != null){
					IceCandidate cand = new IceCandidate(candidate.get("candidate").getAsString(),candidate.get("sdpMid").getAsString(),candidate.get("sdpMLineIndex").getAsInt());
				    user.addCandidate(cand);
				}
				break;
			}case "stop":{
				stop(session);
				break;
			}
			default:{
				break;
			}
		}
	}
	private void handleErrorResponse(Throwable t,WebSocketSession session,String responseId) throws IOException{
		
		stop(session);
		logger.error(t.getMessage(), t);
		JsonObject response=  new JsonObject();
		response.addProperty("id",responseId);
		response.addProperty("response","rejected");
		response.addProperty("message",t.getMessage());
		session.sendMessage(new TextMessage(response.toString()));
	}
	private void register(WebSocketSession session, JsonObject jsonMessage) throws IOException {
		
		String name = jsonMessage.getAsJsonPrimitive("name").getAsString().toUpperCase();
		UserSession caller = new UserSession(session,name);
		String responseMsg = "accepted";
		if(name.isEmpty()){
			responseMsg = "rejected: empty user name";
		}else if(registry.exists(name)){
			responseMsg = "rejected: user "+ name + " already registered";
		}else{
			registry.register(caller);
		}
		JsonObject response = new JsonObject();
		response.addProperty("id","registerResponse");
		response.addProperty("response", responseMsg);
		caller.sendMessage(response);
	}
	private void call(UserSession caller, JsonObject jsonMessage) throws IOException {
		
		String to = jsonMessage.get("to").getAsString().toUpperCase();
		String from = jsonMessage.get("from").getAsString().toUpperCase();
		JsonObject response = new JsonObject();
		
		if (registry.exists(to)) {
			
			caller.setSdpOffer(jsonMessage.getAsJsonPrimitive("sdpOffer").getAsString());
			caller.setCallingTo(to);
			response.addProperty("id", "incomingCall");
			response.addProperty("from", from);
			
			UserSession callee = registry.getByName(to);
			
			callee.sendMessage(response);
			callee.setCallingFrom(from);
		} else {
			response.addProperty("id", "callResponse");
			response.addProperty("response", "rejected: user '" + to + "' is not registered");
			caller.sendMessage(response);
		}
	}
	private void incomingCallResponse(final UserSession callee, JsonObject jsonMessage)
		      throws IOException {
		    String callResponse = jsonMessage.get("callResponse").getAsString();
		    String from = jsonMessage.get("from").getAsString().toUpperCase();
		    final UserSession calleer = registry.getByName(from);
		    String to = calleer.getCallingTo();

		    if ("accept".equals(callResponse)) {
		      logger.debug("Accepted call from '{}' to '{}'", from, to);
		      CallMediaPipeline pipeline = new CallMediaPipeline(kurento);
		       
		      try {
		    	
		        pipelines.put(calleer.getSessionId(), pipeline);
		        pipelines.put(callee.getSessionId(), pipeline);

		        callee.setWebRtcEndpoint(pipeline.getCalleeWebRtcEP());
		        pipeline.getCalleeWebRtcEP().addIceCandidateFoundListener(
		            new EventListener<IceCandidateFoundEvent>() {

		              @Override
		              public void onEvent(IceCandidateFoundEvent event) {
		                JsonObject response = new JsonObject();
		                response.addProperty("id", "iceCandidate");
		                response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
		                try {
		                  synchronized (callee.getSession()) {
		                    callee.getSession().sendMessage(new TextMessage(response.toString()));
		                  }
		                } catch (IOException e) {
		                  logger.debug(e.getMessage());
		                }
		              }
		            });

		        calleer.setWebRtcEndpoint(pipeline.getCallerWebRtcEP());
		        pipeline.getCallerWebRtcEP().addIceCandidateFoundListener(
		            new EventListener<IceCandidateFoundEvent>() {

		              @Override
		              public void onEvent(IceCandidateFoundEvent event) {
		                JsonObject response = new JsonObject();
		                response.addProperty("id", "iceCandidate");
		                response.add("candidate", JsonUtils.toJsonObject(event.getCandidate()));
		                try {
		                  synchronized (calleer.getSession()) {
		                    calleer.getSession().sendMessage(new TextMessage(response.toString()));
		                  }
		                } catch (IOException e) {
		                  logger.debug(e.getMessage());
		                }
		              }
		            });

		        String calleeSdpOffer = jsonMessage.get("sdpOffer").getAsString();
		        String calleeSdpAnswer = pipeline.generateSdpAnswerForCallee(calleeSdpOffer);
		        JsonObject startCommunication = new JsonObject();
		        startCommunication.addProperty("id", "startCommunication");
		        startCommunication.addProperty("sdpAnswer", calleeSdpAnswer);

		        synchronized (callee) {
		          callee.sendMessage(startCommunication);
		        }

		        pipeline.getCalleeWebRtcEP().gatherCandidates();

		        String callerSdpOffer = registry.getByName(from).getSdpOffer();
		        String callerSdpAnswer = pipeline.generateSdpAnswerForCaller(callerSdpOffer);
		        JsonObject response = new JsonObject();
		        response.addProperty("id", "callResponse");
		        response.addProperty("response", "accepted");
		        response.addProperty("sdpAnswer", callerSdpAnswer);

		        synchronized (calleer) {
		          calleer.sendMessage(response);
		        }

		        pipeline.getCallerWebRtcEP().gatherCandidates();

		      } catch (Throwable t) {
		        logger.error(t.getMessage(), t);

		        if (pipeline != null) {
		          pipeline.release();
		        }

		        pipelines.remove(calleer.getSessionId());
		        pipelines.remove(callee.getSessionId());

		        JsonObject response = new JsonObject();
		        response.addProperty("id", "callResponse");
		        response.addProperty("response", "rejected");
		        calleer.sendMessage(response);

		        response = new JsonObject();
		        response.addProperty("id", "stopCommunication");
		        callee.sendMessage(response);
		      }

		    } else {
		      JsonObject response = new JsonObject();
		      response.addProperty("id", "callResponse");
		      response.addProperty("response", "rejected");
		      calleer.sendMessage(response);
		    }
    }
	public void stop(WebSocketSession session) throws IOException {
		
	
		String sessionId = session.getId();
		if (pipelines.containsKey(sessionId)) {
				pipelines.get(sessionId).release();
				CallMediaPipeline pipeline = pipelines.remove(sessionId);
				pipeline.release();
				// Both users can stop the communication. A 'stopCommunication'
				// message will be sent to the other peer.
				UserSession stopperUser = registry.getBySession(session);
				if (stopperUser != null) {
				UserSession stoppedUser = (stopperUser.getCallingFrom() != null)
				? registry.getByName(stopperUser.getCallingFrom())
				: stopperUser.getCallingTo() != null
				? registry.getByName(stopperUser.getCallingTo())
				: null;
				
						if (stoppedUser != null) {
							JsonObject message = new JsonObject();
							message.addProperty("id", "stopCommunication");
							stoppedUser.sendMessage(message);
							stoppedUser.clear();
						}
							stopperUser.clear();
				}
	   }
	}
	
}
