/*
 * Copyright 2007-2008 Sun Microsystems, Inc.
 *
 * This file is part of Project Darkstar Server.
 *
 * Project Darkstar Server is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation and
 * distributed hereunder to you.
 *
 * Project Darkstar Server is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.sun.sgs.app;

/**
 * Provides access to facilities available in the current application context.
 * This class should not be instantiated.
 */
public final class AppContext {
    
    // the current locator for this context
    private static volatile ManagerLocator managerLocator;
    
    /** This class should not be instantiated. */
    private AppContext() { }

    /**
     * Returns the {@code ChannelManager} for use by the current
     * application.  The object returned is not serializable, and should not be
     * stored as part of a managed object.
     *
     * @return	the {@code ChannelManager} for the current application
     * @throws	ManagerNotFoundException if the {@code ChannelManager} cannot
     *          be located
     */
    public static ChannelManager getChannelManager() {
        if(managerLocator == null)
            throw new ManagerNotFoundException("AppContext not initialized");
        
        return managerLocator.getChannelManager();
    }

    /**
     * Returns the {@code DataManager} for use by the current application.
     * The object returned is not serializable, and should not be stored as
     * part of a managed object.
     *
     * @return	the {@code DataManager} for the current application
     * @throws	ManagerNotFoundException if the {@code DataManager} cannot
     *          be located
     */
    public static DataManager getDataManager() {
        if(managerLocator == null)
            throw new ManagerNotFoundException("AppContext not initialized");
        
        return managerLocator.getDataManager();
    }

    /**
     * Returns the {@code TaskManager} for use by the current application.
     * The object returned is not serializable, and should not be stored as
     * part of a managed object.
     *
     * @return	the {@code TaskManager} for the current application
     * @throws	ManagerNotFoundException if the {@code TaskManager} cannot
     *          be located
     */
    public static TaskManager getTaskManager() {
        if(managerLocator == null)
            throw new ManagerNotFoundException("AppContext not initialized");
        
        return managerLocator.getTaskManager();
    }

    /**
     * Returns a manager of the specified type for use by the current
     * application.  The object returned is not serializable, and should not be
     * stored as part of a managed object.
     *
     * @param	<T> the type of the manager
     * @param	type a class representing the type of the manager
     * @return	the manager of the specified type for the current application
     * @throws	ManagerNotFoundException if no manager is found for the
     *		specified type
     */
    public static <T> T getManager(Class<T> type) {
        if(managerLocator == null)
            throw new ManagerNotFoundException("AppContext not initialized");
        
        return managerLocator.getManager(type);
    }
    
    /**
     * Sets the {@code ManagerLocator} which is used to retrieve
     * managers for the application.  <p>
     * 
     * In most situations, this method
     * should only be called once upon bootup of a Project Darkstar
     * container.  Typically, an application should never have a reason
     * to call this method, and doing so could cause unexpected
     * results.
     * 
     * @param managerLocator the {@code ManagerLocator} that the 
     *        {@code AppContext} should use to retrieve managers
     */
    public static synchronized void 
            setManagerLocator(ManagerLocator managerLocator) {
        if(managerLocator == null)
            throw new NullPointerException("managerLocator cannot be null");
        
        AppContext.managerLocator = managerLocator;
    }

}
