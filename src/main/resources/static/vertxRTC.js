(function () {
    'use strict';

    class WebSocketService {
        constructor($log, $q) {
            this.$log = $log;
            this.$q = $q;
            this.cbks = [];

            this.connect();
        }

        connect() {
            this.websocket = new WebSocket("ws://localhost:8080/rtc");
            this.websocket.onopen = this.onOpen.bind(this);
            this.websocket.onclose = this.onClose.bind(this);
            this.websocket.onmessage = this.onMessage.bind(this);
            this.websocket.onerror = this.onError.bind(this);

            this.connectDeferred = this.$q.defer();

        }

        onError(err) {
            this.$log.error("While opening websocket", err);
        }

        onOpen(data) {
            this.cbks.forEach(function (cbk) {
                cbk.onopen && cbk.onopen(data);
            });
            this.connectDeferred.resolve(this.websocket);
        }

        onClose(data) {
            this.cbks.forEach(function (cbk) {
                cbk.onclose && cbk.onclose(data);
            });

            this.connectDeferred = this.$q.defer();
        }

        onMessage(msg) {
            if (msg.type === "message" && msg.data) {
                this.cbks.forEach(function (cbk) {
                    cbk.onmessage && cbk.onmessage(msg.data);
                });
            } else {
                this.$log.error(msg);
            }
        }

        register(cbk) {
            this.cbks.push(cbk);
        }

        send(data) {
            return this.connectDeferred.promise.then(ws=>ws.send(JSON.stringify(data)));
        }
    }

    WebSocketService.$inject = ['$log', '$q'];


    class WebRTC {
        constructor($log, $q, webSocket, rtcConfiguration, mediaConstraints) {
            this.$log = $log;
            this.$q = $q;
            this.webSocket = webSocket;
            this.rtcConfiguration = rtcConfiguration;
            this.mediaConstraints = mediaConstraints;

            this.localVideo = document.getElementById('localVideo');
            this.remoteVideo = document.getElementById('remoteVideo');
        }

        call(pc) {
            const self = this;
            const cp = this.$q.defer();
            const candidates = [];
            pc.onicecandidate = function (evt) {
                if (evt.candidate) {
                    candidates.push(evt.candidate);
                } else {
                    self.$log.info({"ice candidate harvesting complete state": pc.iceConnectionState});
                    cp.resolve(candidates);
                }

            };
            pc.onsignalingstatechange = function (evt) {
                self.$log.info({"onsignalingstatechange": evt, "state": pc.iceConnectionState});
            };
            pc.oniceconnectionstatechange = function (evt) {
                self.$log.info({"oniceconnectionstatechange": evt, "state": pc.iceConnectionState});
            };

            pc.onaddstream = function (evt) {
                self.$log.info({"onaddstream": evt});
                self.remoteVideo.srcObject = evt.stream;
                self.remoteVideo.play();
            };

            const promisedOffer = self.$q.defer();
            navigator.mediaDevices.getUserMedia(self.mediaConstraints).then(stream => {
                pc.addStream(stream);

                self.localVideo.srcObject = stream;
                self.localVideo.play();


                pc.createOffer().then(offer => {
                    pc.setLocalDescription(offer).then(() => {
                        self.$log.debug({"createOffer-setLocalDescription": offer});
                        promisedOffer.resolve(offer);
                    }).catch(err => {
                        self.$log.error({"createOffer-setLocalDescription": err});
                        promisedOffer.reject(err);
                    });
                }).catch(err => {
                    self.$log.error({"createOffer": err});
                    promisedOffer.reject(err);
                });
            }).catch(err => {
                self.$log.error({"getUserMedia": err});
                promisedOffer.reject(err);
            });


            return self.$q.all([cp.promise, promisedOffer.promise]);
        }

        pickup(offer) {
            const self = this;
            const promisedAnswer = this.$q.defer();

            const pc = new RTCPeerConnection(this.rtcConfiguration);
            pc.onicecandidate = function (evt) {
                self.$log.debug({"onicecandidate": evt});
            };

            pc.onaddstream = function (evt) {
                self.$log.debug({"onaddstream": evt});
            };

            navigator.mediaDevices.getUserMedia(self.mediaConstraints)
                .then(stream => {
                    pc.addStream(stream);

                    pc.setRemoteDescription(offer)
                        .then(() => {
                            pc.createAnswer()
                                .then(answer => {
                                    pc.setLocalDescription(answer).then(() => {
                                        self.$log.debug({"answer": answer});
                                        promisedAnswer.resolve(answer);
                                    }).catch(err => {
                                        self.$log.error({"createAnswer-setLocalDescription": answer});
                                        promisedAnswer.reject(err);
                                    });

                                }).catch(err => {
                                self.$log.error({"answer": err});
                                promisedAnswer.reject(err);
                            });
                        }).catch(err => {
                        self.$log.error({"setRemoteDescripton": err, "offer": offer});
                        promisedAnswer.reject(err);
                    });
                }).catch(err => {
                self.$log.error({"getUserMedia": err});
                promisedAnswer.reject(err);
            });
            return promisedAnswer.promise;
        }
    }

    WebRTC.$inject = ['$log', '$q', 'webSocket', 'rtcConfiguration', 'mediaConstraints'];

    class MainCtrl {
        constructor($scope, $log, webrtc, webSocket, rtcConfiguration, mediaConstraints) {
            this.$scope = $scope;
            this.$log = $log;
            this.webrtc = webrtc;
            this.webSocket = webSocket;
            this.rtcConfiguration = rtcConfiguration;
            this.mediaConstraints = mediaConstraints;
        }

        $onInit() {
            const self = this;
            this.localVideo = document.getElementById('localVideo');
            this.remoteVideo = document.getElementById('remoteVideo');

            [this.localVideo, this.remoteVideo].forEach(v => {
                v.onloadedmetadata = function (m) {
                    self.$log.info({onloadedmetadata: m});
                }
            });


            this.webSocket.register({
                onMessage: msg => {
                    var data = JSON.parse(msg);
                    if (data.candidates) {
                        data.candidates.forEach(function (c) {
                            self.pc.addIceCandidate(new RTCIceCandidate(c));
                        })
                    }
                    if (data.sdp && "answer" === data.type) {
                        self.pc.setRemoteDescription(new RTCSessionDescription(data))
                            .then(data => {
                                self.$log.info({setRemoteDescription: data});
                            }).catch(err => {
                            self.$log.error({"setRemoteDescription": err, "answer": data.sdp});
                        });
                    }

                },
                onClose: data => {
                    self.$log.debug('webSocket closed', data);
                    if (self.pc) {
                        self.pc.close();
                    }
                    self.localVideo.src = null;
                },
                onOpen: data => {
                    self.$log.debug('webSocket opened', data);
                }

            });

            this.$scope.call = this.call.bind(this);
            this.$scope.pickup = this.pickup.bind(this);
        }

        call() {
            const self = this;
            if (this.pc) {
                if (this.pc.signalingState !== 'closed') {
                    this.pc.close();
                }
            }
            this.pc = new RTCPeerConnection(self.rtcConfiguration);


            this.webrtc.call(this.pc).then(function (oMsg) {
                var cands = oMsg[0];
                var offer = oMsg[1];

                const a = self.webSocket.send({
                    type: 'offer',
                    sdp: offer.sdp,
                    candidates: cands
                });
                if (a) {
                    self.$scope.offer = offer;
                    self.localVideo.play();
                }
            });
        }

        pickup() {
            this.webrtc.pickup(this.$scope.offer);
        }
    }

    MainCtrl.$inject = ['$scope', '$log', 'webrtc', 'webSocket', 'rtcConfiguration', 'mediaConstraints'];
    var app = angular.module('vertxRTC', [])
        .value('rtcConfiguration', {
            iceServers: [/*{
                "urls": "turn:localhost:30000",
                "username": "turn",
                "credential": "turn"

            }*/{
                "urls": "stun:localhost:30000",
            }]
        })
        .value('mediaConstraints', {
            video: {
                width: {min: 160, max: 640},
                height: {min: 120, max: 480}
            }, audio: false
        })
        .service('webSocket', WebSocketService)
        .service('webrtc', WebRTC)
        .controller('MainCtrl', MainCtrl);
})();