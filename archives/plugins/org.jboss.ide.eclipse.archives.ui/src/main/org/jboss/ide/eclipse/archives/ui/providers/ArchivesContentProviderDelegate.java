/*******************************************************************************
 * Copyright (c) 2007 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package org.jboss.ide.eclipse.archives.ui.providers;

import java.util.ArrayList;
import java.util.Arrays;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IResourceDeltaVisitor;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jface.viewers.ITreeContentProvider;
import org.eclipse.jface.viewers.StructuredViewer;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.swt.widgets.Display;
import org.jboss.ide.eclipse.archives.core.build.RegisterArchivesJob;
import org.jboss.ide.eclipse.archives.core.build.RegisterArchivesJob.RegistrationCallback;
import org.jboss.ide.eclipse.archives.core.model.ArchivesModel;
import org.jboss.ide.eclipse.archives.core.model.IArchiveModel;
import org.jboss.ide.eclipse.archives.core.model.IArchiveModelListener;
import org.jboss.ide.eclipse.archives.core.model.IArchiveModelRootNode;
import org.jboss.ide.eclipse.archives.core.model.IArchiveNode;
import org.jboss.ide.eclipse.archives.core.model.IArchiveNodeDelta;
import org.jboss.ide.eclipse.archives.ui.PrefsInitializer;
import org.jboss.ide.eclipse.archives.ui.views.ProjectArchivesCommonView;

/**
 *
 * @author "Rob Stryker" <rob.stryker@redhat.com>
 *
 */
public class ArchivesContentProviderDelegate implements ITreeContentProvider, IArchiveModelListener {

	public static class WrappedProject {
		public static final int NAME = 1;
		public static final int CATEGORY = 2;
		private IProject element;
		private int type;
		public WrappedProject(IProject element, int type) { 
			this.element = element; this.type = type;
		}
		public IProject getElement() { return element; }
		public int getType() { return type; }
		public boolean equals(Object otherObject) {
			if( otherObject instanceof WrappedProject && element.equals(((WrappedProject)otherObject).element))
					return true;
			return false;
		}
		public String toString() { return element.toString() + "," + type;} //$NON-NLS-1$
		public int hashCode() {
			return element.hashCode() + type;
		}
	}

	public static class DelayProxy {
		public WrappedProject wProject;
		public IProject project;
		public DelayProxy(WrappedProject wp) {
			this.wProject = wp;
			this.project = wProject.element;
		}
		public boolean equals(Object otherObject) {
			return otherObject instanceof DelayProxy &&
				wProject.equals(((DelayProxy)otherObject).wProject);
		}
		public int hashCode() {
			return wProject.hashCode() + 15;
		}
		public String toString() {
			return wProject.toString();
		}
	}

	private int type;
	public ArchivesContentProviderDelegate(int type) {
		this.type = type;
		ArchivesModel.instance().addModelListener(this);
		addWorkspaceListener();
	}
	public ArchivesContentProviderDelegate(boolean addListener) {
		this.type = WrappedProject.NAME;
		if( addListener)
			ArchivesModel.instance().addModelListener(this);
		addWorkspaceListener();
	}

	private IResourceChangeListener workspaceListener;
	private void addWorkspaceListener() {
		workspaceListener = new IResourceChangeListener(){
			public void resourceChanged(
					IResourceChangeEvent event) {
				IResource r = event.getResource();
				int t = event.getType();
				boolean shouldClear = t == IResourceChangeEvent.PRE_CLOSE || 
						t == IResourceChangeEvent.PRE_DELETE || t == IResourceChangeEvent.PRE_REFRESH;
				IProject[] toClear = new IProject[0];
				if( r instanceof IProject && shouldClear) {
					toClear = new IProject[]{(IProject)r};
				} else if( r == null ) {
					toClear = discoverChangedProjects(event);
				}
				for( int i = 0; i < toClear.length; i++ ) {
					if( toClear[i] != null && failedLoads.contains(toClear[i])) {
						failedLoads.remove(toClear[i]);
						safeRefreshViewer(toClear[i]);
					}
				}
			}
		};
		ResourcesPlugin.getWorkspace().addResourceChangeListener(workspaceListener);
	}
	private IProject[] discoverChangedProjects(IResourceChangeEvent event) {
		final ArrayList<IProject> toClear = new ArrayList<IProject>();
		IResourceDelta delta = event.getDelta();
		try {
			delta.accept(new IResourceDeltaVisitor(){
				public boolean visit(IResourceDelta delta)
						throws CoreException {
					IPath projectRel = delta.getResource().getProjectRelativePath();
					if( projectRel != null && projectRel.segmentCount() == 1 
							&& projectRel.lastSegment().equals(IArchiveModel.DEFAULT_PACKAGES_FILE)) {
						toClear.add(delta.getResource().getProject());
					}
					if( delta.getResource() instanceof IWorkspaceRoot || delta.getResource() instanceof IProject )
						return true;
					return false;
				}});
		} catch( CoreException ce) {
			// Ignore
		}
		return toClear.toArray(new IProject[toClear.size()]);
	}
	private void removeWorkspaceListener() {
		ResourcesPlugin.getWorkspace().removeResourceChangeListener(workspaceListener);
	}
	
	protected Viewer viewerInUse;
	protected ArrayList<IProject> loadingProjects = new ArrayList<IProject>();
	private ArrayList<IProject> failedLoads = new ArrayList<IProject>();

	public Object[] getChildren(Object parentElement) {
		if( parentElement instanceof WrappedProject ) {
			WrappedProject wp = (WrappedProject)parentElement;
			IProject p = ((WrappedProject)parentElement).getElement();

			// if currently loading, always send a delay
			if( loadingProjects.contains(p))
				return new Object[]{new DelayProxy(wp)};
			if( failedLoads.contains(p))
				return new Object[]{"Error parsing project archives descriptor."}; //$NON-NLS-1$
			if( !p.isOpen()) 
				return new Object[]{};
			
			if( ArchivesModel.instance().isProjectRegistered(p.getLocation()))
				return ArchivesModel.instance().getRoot(p.getLocation()).getAllChildren();
			if( ArchivesModel.instance().canReregister(p.getLocation())) {
				loadingProjects.add(p);
				DelayProxy dp = new DelayProxy(wp);
				launchRegistrationThread(dp);
				return new Object[]{dp};
			}
		}
		if( parentElement instanceof IArchiveNode )
			return ((IArchiveNode)parentElement).getAllChildren();
		return new Object[0];
	}


	protected void launchRegistrationThread(final DelayProxy dp) {
		RegistrationCallback callback = new RegistrationCallback() {
			public void run() {
				Display.getDefault().asyncExec(new Runnable() {
					public void run() {
						loadingProjects.remove(dp.project);
						refreshViewer(dp.wProject);
					}
				});
			}

			public void registrationFailed() {
				Display.getDefault().asyncExec(new Runnable() {
					public void run() {
						if( !failedLoads.contains(dp.project)) {
							failedLoads.add(dp.project);
						}
						loadingProjects.remove(dp.project);
						refreshViewer(dp.wProject);
					}
				});
			}
		};
		RegisterArchivesJob job = new RegisterArchivesJob(new IProject[]{dp.project}, callback);
		job.schedule();
	}

	protected boolean shouldRefreshProject() {
		if( ProjectArchivesCommonView.getInstance() != null && 
				viewerInUse == ProjectArchivesCommonView.getInstance().getCommonViewer() &&
				!PrefsInitializer.getBoolean(PrefsInitializer.PREF_SHOW_PROJECT_ROOT))
			return true;
		return false;
	}

	public Object getParent(Object element) {
		return null;
	}

	public boolean hasChildren(Object element) {
		if( element instanceof IArchiveNode )
			return getChildren(element).length > 0;
		if( element instanceof IResource )
			return 
				((IResource)element).getProject().isOpen() && 
			ArchivesModel.instance().canReregister(((IResource)element).getLocation());
		if( element == ArchivesRootContentProvider.NO_PROJECT)
			return false;
		if( element instanceof DelayProxy)
			return false;
		return true;
	}

	public Object[] getElements(Object inputElement) {
		return getChildren(inputElement);
	}

	public void dispose() {
		ArchivesModel.instance().removeModelListener(this);
		removeWorkspaceListener();
	}

	public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
		viewerInUse = viewer;
	}
	public void modelChanged(IArchiveNodeDelta delta) {

		final IArchiveNode[] topChanges;
		if( delta.getKind() == IArchiveNodeDelta.DESCENDENT_CHANGED)
			topChanges = getChanges(delta);
		else if( delta.getKind() == IArchiveNodeDelta.NO_CHANGE)
			return;
		else if( delta.getPostNode() == null )
			topChanges = null;
		else 
			topChanges = new IArchiveNode[]{delta.getPostNode()};

		// now go through and refresh them
		Display.getDefault().asyncExec(new Runnable () {
			public void run () {
				if( topChanges == null ) {
					refreshViewer(null);
					return;
				}
				for( int i = 0; i < topChanges.length; i++ ) {
					refreshViewer(topChanges[i]);
				}
			}
		});
	}

	protected void safeRefreshViewer(final Object o) {
		Display.getDefault().asyncExec(new Runnable() {
			public void run() {
				refreshViewer(o);
			}
		});
	}
	/*
	 * The inputs to ProjectArchivesCommonView are either an IProject, if no root projects are shown,
	 * or an IWorkspaceRoot, if they are. A parent, though can be a WrappedProject
	 */

	protected void refreshViewer(Object o) {
		if( o instanceof IArchiveModelRootNode) {
			String projName = ((IArchiveModelRootNode)o).getProjectName();
			if( projName == null )
				return;
			IProject p = ResourcesPlugin.getWorkspace().getRoot().getProject(projName);
			if( p != null )
				o = new WrappedProject(p, type);
		}
		if( o instanceof WrappedProject && shouldRefreshProject())
			o = ((WrappedProject)o).element;

		if( !viewerInUse.getControl().isDisposed()) {
			if( viewerInUse instanceof StructuredViewer ) {
				if( o == null ) {
					((StructuredViewer)viewerInUse).refresh();
				} else {
					((StructuredViewer)viewerInUse).refresh(o);
					if( viewerInUse instanceof TreeViewer ) {
						((TreeViewer)viewerInUse).expandToLevel(o, 1);
					}
				}
			} else
				viewerInUse.refresh();
		}
	}
	protected IArchiveNode[] getChanges(IArchiveNodeDelta delta) {
		IArchiveNodeDelta[] children = delta.getAllAffectedChildren();
		ArrayList<IArchiveNode> list = new ArrayList<IArchiveNode>();
		for( int i = 0; i < children.length; i++ ) {
			if( children[i].getKind() == IArchiveNodeDelta.DESCENDENT_CHANGED)
				list.addAll(Arrays.asList(getChanges(children[i])));
			else
				list.add(children[i].getPostNode());
		}
		return list.toArray(new IArchiveNode[list.size()]);
	}

}
