/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch;

import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.xpack.security.action.role.GetRolesResponse;
import org.elasticsearch.xpack.security.action.role.PutRoleResponse;
import org.elasticsearch.xpack.security.action.user.GetUsersResponse;
import org.elasticsearch.xpack.security.action.user.PutUserResponse;
import org.elasticsearch.xpack.security.authc.esnative.NativeUsersStore;
import org.elasticsearch.xpack.security.authc.support.UsernamePasswordToken;
import org.elasticsearch.xpack.security.authz.RoleDescriptor;
import org.elasticsearch.xpack.security.authz.store.NativeRolesStore;
import org.elasticsearch.xpack.security.client.SecurityClient;
import org.elasticsearch.xpack.security.user.User;

import java.util.Collections;

import static java.util.Collections.singletonMap;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.elasticsearch.xpack.security.authc.support.UsernamePasswordTokenTests.basicAuthHeaderValue;
import static org.hamcrest.Matchers.arrayWithSize;

/**
 * Backwards compatibility test that loads some data from a pre-5.0 cluster and attempts to do some basic security stuff with it. It
 * contains:
 * <ul>
 *  <li>This user: {@code {"username": "bwc_test_user", "roles" : [ "bwc_test_role" ], "password" : "9876543210"}}</li>
 *  <li>This role: {@code {"name": "bwc_test_role", "cluster": ["all"]}, "run_as": [ "other_user" ], "indices": [{
 *    "names": [ "index1", "index2" ],
 *    "privileges": ["all"],
 *    "fields": [ "title", "body" ],
 *    "query": "{\"match\": {\"title\": \"foo\"}}"
 *   }]}</li>
 *  <li>This document in {@code index1}: {@code {
 *   "title": "foo",
 *   "body": "bwc_test_user should be able to see this field",
 *   "secured_body": "bwc_test_user should not be able to see this field"}}</li>
 *  <li>This document in {@code index1}: {@code {"title": "bwc_test_user should not be able to see this document"}}</li>
 *  <li>This document in {@code index2}: {@code {
 *   "title": "foo",
 *   "body": "bwc_test_user should be able to see this field",
 *   "secured_body": "bwc_test_user should not be able to see this field"}}</li>
 *  <li>This document in {@code index2}: {@code {"title": "bwc_test_user should not be able to see this document"}}</li>
 *  <li>This document in {@code index3}: {@code {"title": "bwc_test_user should not see this index"}}</li>
 * </ul>
 **/
public class OldSecurityIndexBackwardsCompatibilityIT extends AbstractOldXPackIndicesBackwardsCompatibilityTestCase {
    @Override
    protected boolean shouldTestVersion(Version version) {
        return version.onOrAfter(Version.V_2_3_0); // native realm only supported from 2.3.0 on
    }

    protected void checkVersion(Version version) throws Exception {
        // test that user and roles are there
        logger.info("Getting roles...");
        SecurityClient securityClient = new SecurityClient(client());
        assertBusy(() -> {
            assertEquals(NativeRolesStore.State.STARTED, internalCluster().getInstance(NativeRolesStore.class).state());
        });
        GetRolesResponse getRolesResponse = securityClient.prepareGetRoles("bwc_test_role").get();
        assertThat(getRolesResponse.roles(), arrayWithSize(1));
        RoleDescriptor role = getRolesResponse.roles()[0];
        assertEquals("bwc_test_role", role.getName());
        assertThat(role.getIndicesPrivileges(), arrayWithSize(1));
        RoleDescriptor.IndicesPrivileges indicesPrivileges = role.getIndicesPrivileges()[0];
        assertThat(indicesPrivileges.getIndices(), arrayWithSize(2));
        assertArrayEquals(new String[] { "index1", "index2" }, indicesPrivileges.getIndices());
        assertArrayEquals(new String[] { "title", "body" }, indicesPrivileges.getFields());
        assertArrayEquals(new String[] { "all" }, indicesPrivileges.getPrivileges());
        assertEquals("{\"match\": {\"title\": \"foo\"}}", indicesPrivileges.getQuery().utf8ToString());
        assertArrayEquals(new String[] { "all" }, role.getClusterPrivileges());
        assertArrayEquals(new String[] { "other_user" }, role.getRunAs());
        assertEquals("bwc_test_role", role.getName());

        logger.info("Getting users...");
        assertBusy(() -> {
            assertEquals(NativeUsersStore.State.STARTED, internalCluster().getInstance(NativeUsersStore.class).state());
        });
        GetUsersResponse getUsersResponse = securityClient.prepareGetUsers("bwc_test_user").get();
        assertThat(getUsersResponse.users(), arrayWithSize(1));
        User user = getUsersResponse.users()[0];
        assertArrayEquals(new String[] { "bwc_test_role" }, user.roles());
        assertEquals("bwc_test_user", user.principal());

        // check that documents are there
        assertHitCount(client().prepareSearch("index1", "index2", "index3").get(), 5);

        Client bwcTestUserClient = client().filterWithHeader(
                singletonMap(UsernamePasswordToken.BASIC_AUTH_HEADER, basicAuthHeaderValue("bwc_test_user", "9876543210")));
        // check that index permissions work as expected
        SearchResponse searchResponse = bwcTestUserClient.prepareSearch("index1", "index2").get();
        assertEquals(2, searchResponse.getHits().getTotalHits());
        assertEquals("foo", searchResponse.getHits().getHits()[0].getSource().get("title"));
        assertEquals("bwc_test_user should be able to see this field", searchResponse.getHits().getHits()[0].getSource().get("body"));
        assertNull(searchResponse.getHits().getHits()[0].getSource().get("secured_body"));
        assertEquals("foo", searchResponse.getHits().getHits()[1].getSource().get("title"));
        assertEquals("bwc_test_user should be able to see this field", searchResponse.getHits().getHits()[1].getSource().get("body"));
        assertNull(searchResponse.getHits().getHits()[1].getSource().get("secured_body"));

        Exception e = expectThrows(ElasticsearchSecurityException.class, () -> bwcTestUserClient.prepareSearch("index3").get());
        assertEquals("action [indices:data/read/search] is unauthorized for user [bwc_test_user]", e.getMessage());

        // try adding a user
        PutRoleResponse roleResponse = securityClient.preparePutRole("test_role").addIndices(
                new String[] { "index3" },
                new String[] { "all" },
                new String[] { "title", "body" },
                new BytesArray("{\"term\": {\"title\":\"not\"}}")).cluster("all")
                .get();
        assertTrue(roleResponse.isCreated());
        PutUserResponse userResponse = securityClient.preparePutUser("another_bwc_test_user", "123123".toCharArray(), "test_role")
                .email("a@b.c").get();
        assertTrue(userResponse.created());
        searchResponse = client().filterWithHeader(
                Collections.singletonMap(UsernamePasswordToken.BASIC_AUTH_HEADER,
                        basicAuthHeaderValue("another_bwc_test_user", "123123")
                )).prepareSearch("index3").get();
        assertEquals(1, searchResponse.getHits().getTotalHits());
        assertEquals("bwc_test_user should not see this index", searchResponse.getHits().getHits()[0].getSource().get("title"));

        userResponse = securityClient.preparePutUser("meta_bwc_test_user", "123123".toCharArray(), "test_role").email("a@b.c")
                .metadata(singletonMap("test", 1)).get();
        assertTrue(userResponse.created());

        getUsersResponse = securityClient.prepareGetUsers("meta_bwc_test_user").get();
        assertThat(getUsersResponse.users(), arrayWithSize(1));
        user = getUsersResponse.users()[0];
        assertArrayEquals(new String[] { "test_role" }, user.roles());
        assertEquals("meta_bwc_test_user", user.principal());
    }
}
