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
	private JFrame frame;
	
	public void close(){
		frame.setVisible(false);
		frame.dispose();
		
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
						
				});
				
			}
			
			@Override
			public void videoRemoved(VideoEvent event) {
				System.out.println("videoRemoved:("+event.getClass().getSimpleName()+"):"+event.getOrigin());
				
				SwingUtilities.invokeLater(()->{
					int origin = event.getOrigin();
					try{
						frame.remove(origin-1);
					}catch (ArrayIndexOutOfBoundsException ae){
						ae.getMessage();
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
						frame.add(cmp,BorderLayout.WEST);
					}else if(origin == VideoEvent.REMOTE){
						frame.add(cmp,BorderLayout.EAST);
					}else{
						frame.add(cmp,BorderLayout.CENTER);
					}
					
					
					
				});
				
				
				
			}
		});
		
		try {
			SwingUtilities.invokeAndWait(()->{
				frame = new JFrame();
				frame.setTitle("MediaPlayer");
				frame.setSize(640, 240);
				frame.setLayout(new BorderLayout());
				
				frame.setVisible(true);
			});
		} catch (HeadlessException | InvocationTargetException | InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		
	}
	
	
}
