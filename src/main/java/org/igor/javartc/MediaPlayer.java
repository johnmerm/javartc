package org.igor.javartc;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GridLayout;
import java.awt.HeadlessException;
import java.lang.reflect.InvocationTargetException;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.jitsi.service.neomedia.VideoMediaStream;
import org.jitsi.util.event.VideoEvent;
import org.jitsi.util.event.VideoListener;

public class MediaPlayer {

	private VideoMediaStream videoStream;
	private JFrame localFrame,remoteframe;
	
	public void close(){
		localFrame.setVisible(false);
		localFrame.dispose();
		
		remoteframe.setVisible(false);
		remoteframe.dispose();
		
		
	}
	public MediaPlayer(VideoMediaStream videoStream) {
		super();
		this.videoStream = videoStream;
		this.videoStream.addVideoListener(new VideoListener() {
			
			@Override
			public void videoUpdate(VideoEvent event) {
				Component cmp = event.getVisualComponent();
				
				System.out.println("videoUpdate("+event.getClass().getSimpleName()+"):"+event.getOrigin()+":"+cmp.getWidth()+"x"+cmp.getHeight());
				
				SwingUtilities.invokeLater(()->{
					int origin = event.getOrigin();
					
					if (origin == VideoEvent.LOCAL){
						localFrame.add(cmp);
					}else if(origin == VideoEvent.REMOTE){
						remoteframe.add(cmp);
					}else{
					}	
				});
				
			}
			
			@Override
			public void videoRemoved(VideoEvent event) {
				System.out.println("videoRemoved:("+event.getClass().getSimpleName()+"):"+event.getOrigin());
				
				SwingUtilities.invokeLater(()->{
					int origin = event.getOrigin();
					Component cmp = event.getVisualComponent();
					if (origin == VideoEvent.LOCAL){
						localFrame.remove(cmp);
					}else if(origin == VideoEvent.REMOTE){
						remoteframe.remove(cmp);
					}else{
					}
				});
			}
			
			@Override
			public void videoAdded(VideoEvent event) {
				Component cmp = event.getVisualComponent();
				cmp.setSize(320, 240);
				System.out.println("videoAdded:("+event.getClass().getSimpleName()+"):"+event.getOrigin()+":"+cmp.getWidth()+"x"+cmp.getHeight());
				
				SwingUtilities.invokeLater(()->{
					int origin = event.getOrigin();
					if (origin == VideoEvent.LOCAL){
						localFrame.add(cmp);
						localFrame.setVisible(true);
					}else if(origin == VideoEvent.REMOTE){
						remoteframe.add(cmp);
						remoteframe.setVisible(true);
					}else{
					}
					
					
					
				});
				
				
				
			}
		});
		
		try {
			SwingUtilities.invokeAndWait(()->{
				localFrame = new JFrame();
				localFrame.setTitle("local");
				localFrame.setSize(320, 240);
				
				
				
				remoteframe = new JFrame();
				remoteframe.setTitle("Remote");
				remoteframe.setSize(320, 240);
				
				
				
			});
		} catch (HeadlessException | InvocationTargetException | InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		
	}
	
	
}
