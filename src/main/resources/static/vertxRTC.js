(function() {
  'use strict';
  var app = angular.module('vertxRTC',[]);
  
  app
  .value('rtcConfiguration',{ iceServers: [{
	  "urls": "turn:localhost:30000",
	  "username":"turn",
	  "credential":"turn"
		  
  }]})
  .value('mediaConstraints',{
	  video:{ 
		  width:{min:160,max:640},
		  height:{min:120,max:480}
	  },audio:false
  })
  .factory('sockjs',function($log,$q){
	  var sock = new SockJS("http://localhost:8080/rtc");
	  var isOpen = false;
	  var cbks = [];
	  var sockPromise = $q.defer();
	  
	  sock.onopen = function(data) {
		isOpen = true;
		cbks.forEach(function(cbk) {
			cbk.onopen && cbk.onopen(data);
		});
	  };
	  sock.onclose = function(data) {
		isOpen=false;
		cbks.forEach(function(cbk) {
			cbk.onclose && cbk.onclose(data);
		});
	  };

	  sock.onmessage = function(msg) {
		if (msg.type === "message" && msg.data) {
			cbks.forEach(function(cbk) {
				cbk.onmessage && cbk.onmessage(msg.data);
			});
		} else {
			$log.error(msg);
		}
	  };
	  
	  return {
		register : function(cbk){
		  cbks.push(cbk);	  
		},
	  
	  	send : function(data){
	  	  if (!isOpen){
	  		  sock = new SockJS("http://localhost:8080/rtc");
	  	  }
		  return sock.send(JSON.stringify(data));
	  	}
	  };
  })
  .factory('webrtc',function($log,$q,sockjs,rtcConfiguration,mediaConstraints){
	  return {
		  call: function(pc){
			var cp = $q.defer();
			var candidates = [];
			pc.onicecandidate = function(evt){
				if (evt.candidate){
					candidates.push(evt.candidate);
				}else{
					cp.resolve(candidates);
				}
				
			};
			pc.onsignalingstatechange = function(evt){
				$log.info({"onsignalingstatechange":evt,"state":pc.iceConnectionState});
			}
			pc.oniceconnectionstatechange = function(evt){
				$log.info({"oniceconnectionstatechange":evt,"state":pc.iceConnectionState});
			}
			
			pc.onaddstream = function (evt) {
				$log.info({"onaddstream":evt});
				var remoteVideo = document.getElementById("remoteVideo");
				remoteVideo.src = window.URL.createObjectURL(evt.stream);
				remoteVideo.play();
			};
			
			var promisedOffer = $q.defer();
			navigator.getUserMedia(mediaConstraints,
				function (stream) {
			        pc.addStream(stream);
			        
					var localVideo = document.getElementById("localVideo");
					localVideo.src = window.URL.createObjectURL(stream);
					localVideo.play();

			        
			        pc.createOffer(
			        	function(offer){
			        		pc.setLocalDescription(offer).then(function(){
			        			$log.debug({"createOffer-setLocalDescription":offer});
			        			promisedOffer.resolve(offer);
			        		},function(err){
			        			$log.error({"createOffer-setLocalDescription":err});
			        			promisedOffer.reject(err);
			        		});
						},function(err){
			        		$log.error({"createOffer":err});
			        		promisedOffer.reject(err);
			        	}
			        );
				},function(err){
					$log.error({"getUserMedia":err});
					promisedOffer.reject(err);
				}
			);
			
			return $q.all([cp.promise,promisedOffer.promise]);
		  },
		  
		  pickup:function(offer){
			var promisedAnswer = $q.defer();
			  
			var pc = new RTCPeerConnection(rtcConfiguration);
			pc.onicecandidate = function(evt){
				$log.debug({"onicecandidate":evt});
			};
				
			pc.onaddstream = function (evt) {
				$log.debug({"onaddstream":evt});
			};
			
			navigator.getUserMedia(mediaConstraints,
				function (stream) {
					pc.addStream(stream);
					
					pc.setRemoteDescription(offer).then(
						function(rOffer){
							pc.createAnswer(
								function(answer){
									pc.setLocalDescription(answer).then(function(){
										$log.debug({"answer":answer});
										promisedAnswer.resolve(answer);
									},function(err){
										$log.error({"createAnswer-setLocalDescription":lAnswer});
										promisedAnswer.reject(err);
									});
									
								},function(err){
									$log.error({"answer":err});
									promisedAnswer.reject(err);
								}
							);
						},function(err){
							$log.error({"setRemoteDescripton":err,"offer":offer});
							promisedAnswer.reject(err);
						}
					);
					
				},function(err){
					$log.error({"getUserMedia":err});
					promisedAnswer.reject(err);
				}
			);
			return promisedAnswer.promise;
		  }
	  };
		  
	  
  })
  .controller('MainCtrl',function($scope,$log,webrtc,sockjs,rtcConfiguration,mediaConstraints){
	  
	  var localVideo = document.getElementById('localVideo');
	  var remoteVideo = document.getElementById('remoteVideo');
	  
	  [localVideo,remoteVideo].forEach(function(v){
		  v.onloadedmetadata = function(m){
			  $log.info({onloadedmetadata:m});
		  }
	  });
	  
	  sockjs.register({
		  onmessage:function(msg){
			  var data = JSON.parse(msg);
			  if (data.candidates){
				  data.candidates.forEach(function(c){
					  $scope.pc.addIceCandidate(new RTCIceCandidate(c));
				  })
			  }
			  if (data.sdp && "answer" == data.type){
				  $scope.pc.setRemoteDescription(
						  new RTCSessionDescription(data),
						  function(data){
					  		$log.info({setRemoteDescription:data});
				  		  },function(err){
				  			  $log.error({"setRemoteDescription":err,"answer":data.sdp});
				  		  });
			  }
			  
		  },
		  onclose: function(data){
			  $log.debug('sockjs closed',data);
			  if ($scope.pc){
				  $scope.pc.close(); 
			  }
			  
			  var localVideo = document.getElementById("localVideo");
			  localVideo.src = null;
		  },
		  onopen: function(data){
			  $log.debug('sockjs opened',data);
		  }
		  
	  });
	  
	  $scope.clickBtn = function(){
		  if ($scope.pc){
			  if ($scope.pc.signalingState != 'closed'){
				  $scope.pc.close();
			  }
		  }
		  $scope.pc = new RTCPeerConnection(rtcConfiguration);
		  
		  
		  webrtc.call($scope.pc).then(function (oMsg){
			  var cands = oMsg[0];
			  var offer = oMsg[1];
			  
			  var a = sockjs.send({
				  type:'offer',
				  sdp:offer.sdp,
				  candidates:cands
			  });
			  if (a){
			  
				  $scope.offer = offer;
				  var localVideo = document.getElementById("localVideo");
				  localVideo.play();
			  };
			  
			  
		  });
		  
		  
	  }
	  
	  $scope.clickBtn2 = function(){
		  webrtc.pickup($scope.offer); 
	  }
  });
})();