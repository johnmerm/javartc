package org.igor.javartc;

import java.awt.Component;
import java.awt.HeadlessException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import org.jitsi.service.neomedia.AudioMediaStream;
import org.jitsi.service.neomedia.MediaStream;
import org.jitsi.service.neomedia.VideoMediaStream;
import org.jitsi.util.event.VideoEvent;
import org.jitsi.util.event.VideoListener;

public class MediaPlayer {

	private VideoMediaStream videoStream;
	
	private JFrame frame;
	
	public MediaPlayer(VideoMediaStream videoStream) {
		super();
		this.videoStream = videoStream;
		videoStream.addVideoListener(new VideoListener() {
			
			@Override
			public void videoUpdate(VideoEvent event) {
				Component cmp = event.getVisualComponent();
				System.out.println("videoUpdate("+event.getClass().getSimpleName()+"):"+event.getOrigin()+":"+cmp.getWidth()+"x"+cmp.getHeight());
			}
			
			@Override
			public void videoRemoved(VideoEvent event) {
				System.out.println("videoRemoved:("+event.getClass().getSimpleName()+"):"+event.getOrigin());
				try {
					SwingUtilities.invokeAndWait(()->{
						frame.remove(event.getVisualComponent());
						frame.setVisible(false);
					});
				} catch (InvocationTargetException | InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				
			}
			
			@Override
			public void videoAdded(VideoEvent event) {
				Component cmp = event.getVisualComponent();
				System.out.println("videoAdded:("+event.getClass().getSimpleName()+"):"+event.getOrigin()+":"+cmp.getWidth()+"x"+cmp.getHeight());
				try {
					SwingUtilities.invokeAndWait(()->{
						frame.add(cmp);
						frame.pack();
					});
				} catch (HeadlessException | InvocationTargetException | InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				
				
			}
		});
	
		frame = new JFrame();
		frame.setSize(160,120);
		frame.setTitle("MediaPlayer");
		
		frame.setVisible(true);
	}
	
	
}
