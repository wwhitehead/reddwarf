/*
 * Copyright 2007-2009 Sun Microsystems, Inc.
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

package com.sun.sgs.test.impl.service.data;

import com.sun.sgs.app.DataManager;
import com.sun.sgs.app.ManagedObject;
import com.sun.sgs.app.ManagedReference;
import com.sun.sgs.impl.hook.HookLocator;
import com.sun.sgs.impl.service.data.HookedDataService;
import com.sun.sgs.service.DataService;
import com.sun.sgs.service.data.ManagedObjectReplacementHook;
import com.sun.sgs.tools.test.FilteredNameRunner;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.easymock.EasyMock.*;

@RunWith(FilteredNameRunner.class)
public class TestHookedDataService extends Assert {

    private static final Object ORIGINAL = new Object();
    private static final Object REPLACEMENT = new Object();

    private HookedDataService hookedDataService;
    private DataService backend;

    @Before
    @SuppressWarnings({"unchecked"})
    public void setDummyHook() {
        backend = createMock(DataService.class);
        hookedDataService = new HookedDataService(backend);

        HookLocator.setManagedObjectReplacementHook(new ManagedObjectReplacementHook() {
            public <T> T replaceManagedObject(T object) {
                Assert.assertEquals(ORIGINAL, object);
                return (T) REPLACEMENT;
            }
        });
    }

    @After
    public void resetHook() {
        HookLocator.setManagedObjectReplacementHook(null);
    }

    @Test
    public void hook_affects_the_parameters_of_setBinding() {
        backend.setBinding("foo", REPLACEMENT);
        replay(backend);

        hookedDataService.setBinding("foo", ORIGINAL);
        verify(backend);
    }

    @Test
    public void hook_affects_the_parameters_of_removeObject() {
        backend.removeObject(REPLACEMENT);
        replay(backend);

        hookedDataService.removeObject(ORIGINAL);
        verify(backend);
    }

    @Test
    public void hook_affects_the_parameters_of_markForUpdate() {
        backend.markForUpdate(REPLACEMENT);
        replay(backend);

        hookedDataService.markForUpdate(ORIGINAL);
        verify(backend);
    }

    @Test
    @SuppressWarnings({"unchecked"})
    public void hook_affects_the_parameters_of_createReference() {
        ManagedReference<Object> retval = createMock(ManagedReference.class);
        expect(backend.createReference(REPLACEMENT)).andReturn(retval);
        replay(backend);

        assertEquals(retval, hookedDataService.createReference(ORIGINAL));
        verify(backend);
    }

    @Test
    public void hook_affects_the_parameters_of_getObjectId() {
        BigInteger retval = BigInteger.ONE;
        expect(backend.getObjectId(REPLACEMENT)).andReturn(retval);
        replay(backend);

        assertEquals(retval, hookedDataService.getObjectId(ORIGINAL));
        verify(backend);
    }

    @Test
    public void the_above_tested_methods_are_all_the_DataManager_methods_which_accept_ManagedObject_parameters() {
        List<String> aboveTestedMethods = Arrays.asList(
                "setBinding",
                "removeObject",
                "markForUpdate",
                "createReference",
                "getObjectId"
        );
        Set<String> dataManagerMethodsAcceptingManagedObjects = new HashSet<String>();
        for (Method method : DataManager.class.getMethods()) {
            for (Class<?> parameter : method.getParameterTypes()) {
                if (parameter.isAssignableFrom(ManagedObject.class)) {
                    dataManagerMethodsAcceptingManagedObjects.add(method.getName());
                }
            }
        }
        assertTrue(aboveTestedMethods.containsAll(dataManagerMethodsAcceptingManagedObjects));
        assertTrue(dataManagerMethodsAcceptingManagedObjects.containsAll(aboveTestedMethods));
    }
}