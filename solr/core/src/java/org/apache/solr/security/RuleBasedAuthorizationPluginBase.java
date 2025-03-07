/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.security;

import static java.util.function.Function.identity;
import static java.util.stream.Collectors.toMap;
import static org.apache.solr.handler.admin.SecurityConfHandler.getListValue;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.solr.api.AnnotatedApi;
import org.apache.solr.api.Api;
import org.apache.solr.common.SpecProvider;
import org.apache.solr.common.util.CommandOperation;
import org.apache.solr.common.util.ValidatingJsonMap;
import org.apache.solr.handler.admin.api.ModifyRuleBasedAuthConfigAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Base class for rule based authorization plugins */
public abstract class RuleBasedAuthorizationPluginBase
    implements AuthorizationPlugin, ConfigEditablePlugin, SpecProvider {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final Map<String, WildCardSupportMap> mapping = new HashMap<>();
  private final Map<String, Set<Permission>> roleToPermissionsMap = new HashMap<>();

  // Doesn't implement Map because we violate the contracts of put() and get()
  private static class WildCardSupportMap {
    final Set<String> wildcardPrefixes = new HashSet<>();
    final Map<String, List<Permission>> delegate = new HashMap<>();

    public List<Permission> put(String key, List<Permission> value) {
      if (key != null && key.endsWith("/*")) {
        key = key.substring(0, key.length() - 2);
        wildcardPrefixes.add(key);
      }
      return delegate.put(key, value);
    }

    public List<Permission> get(String key) {
      List<Permission> result = delegate.get(key);
      if (key == null || result != null) return result;

      for (String s : wildcardPrefixes) {
        if (key.startsWith(s)) {
          List<Permission> wildcardPermissions = delegate.get(s);
          if (wildcardPermissions != null) {
            if (result == null) result = new ArrayList<>();
            result.addAll(wildcardPermissions);
          }
        }
      }

      return result;
    }

    public Set<String> keySet() {
      return delegate.keySet();
    }
  }

  @Override
  public AuthorizationResponse authorize(AuthorizationContext context) {
    List<AuthorizationContext.CollectionRequest> collectionRequests =
        context.getCollectionRequests();
    if (log.isDebugEnabled()) {
      log.debug(
          "Attempting to authorize request to [{}] of type: [{}], associated with collections [{}]",
          context.getResource(),
          context.getRequestType(),
          collectionRequests);
    }

    if (context.getRequestType() == AuthorizationContext.RequestType.ADMIN) {
      log.debug("Authorizing an ADMIN request, checking admin permissions");
      MatchStatus flag = checkCollPerm(mapping.get(null), context);
      return flag.rsp;
    }

    for (AuthorizationContext.CollectionRequest collreq : collectionRequests) {
      // check permissions for each collection
      log.debug(
          "Authorizing collection-aware request, checking perms applicable to specific collection [{}]",
          collreq.collectionName);
      MatchStatus flag = checkCollPerm(mapping.get(collreq.collectionName), context);
      if (flag != MatchStatus.NO_PERMISSIONS_FOUND) return flag.rsp;
    }

    log.debug(
        "Authorizing collection-aware request, checking perms applicable to all (*) collections");
    // check wildcard (all=*) permissions.
    MatchStatus flag = checkCollPerm(mapping.get("*"), context);
    return flag.rsp;
  }

  /**
   * Retrieves permission names for a given set of roles.
   *
   * <p>There are two special role names that can be used in the roles list:
   *
   * <ul>
   *   <li><code>null</code> meaning permission granted for all requests, even without a role
   *   <li><code>"*"</code> meaning any role will grant the permission
   * </ul>
   *
   * In order to obtain all permissions a user has based on the user's roles, you also need to
   * include these two special roles to get the full list.
   *
   * @param roles a collection of role names.
   */
  public Set<String> getPermissionNamesForRoles(Collection<String> roles) {
    if (roles == null) {
      return Set.of();
    }
    return roles.stream()
        .filter(roleToPermissionsMap::containsKey)
        .flatMap(r -> roleToPermissionsMap.get(r).stream())
        .map(p -> p.name)
        .collect(Collectors.toSet());
  }

  private MatchStatus checkCollPerm(WildCardSupportMap pathVsPerms, AuthorizationContext context) {
    if (pathVsPerms == null) return MatchStatus.NO_PERMISSIONS_FOUND;

    if (log.isTraceEnabled()) {
      log.trace("Following perms are associated with collection");
      for (String pathKey : pathVsPerms.keySet()) {
        final List<Permission> permsAssociatedWithPath = pathVsPerms.get(pathKey);
        log.trace("Path: [{}], Perms: [{}]", pathKey, permsAssociatedWithPath);
      }
    }

    String path = context.getResource();
    MatchStatus flag = checkPathPerm(pathVsPerms.get(path), context);
    if (flag != MatchStatus.NO_PERMISSIONS_FOUND) return flag;
    return checkPathPerm(pathVsPerms.get(null), context);
  }

  private MatchStatus checkPathPerm(List<Permission> permissions, AuthorizationContext context) {
    if (permissions == null || permissions.isEmpty()) {
      return MatchStatus.NO_PERMISSIONS_FOUND;
    }

    log.trace("Following perms are associated with this collection and path: [{}]", permissions);
    final Permission governingPermission = findFirstGoverningPermission(permissions, context);
    if (governingPermission == null) {
      if (log.isDebugEnabled()) {
        log.debug(
            "No perms configured for the resource {} . So allowed to access",
            context.getResource());
      }
      return MatchStatus.NO_PERMISSIONS_FOUND;
    }
    if (log.isDebugEnabled()) {
      log.debug(
          "Found perm [{}] to govern resource [{}]", governingPermission, context.getResource());
    }

    return determineIfPermissionPermitsPrincipal(context, governingPermission);
  }

  private Permission findFirstGoverningPermission(
      List<Permission> permissions, AuthorizationContext context) {
    for (int i = 0; i < permissions.size(); i++) {
      Permission permission = permissions.get(i);
      if (permissionAppliesToRequest(permission, context)) return permission;
    }

    return null;
  }

  private boolean permissionAppliesToRequest(Permission permission, AuthorizationContext context) {
    if (log.isTraceEnabled()) {
      log.trace(
          "Testing whether permission [{}] applies to request [{}]",
          permission,
          context.getResource());
    }
    if (PermissionNameProvider.values.containsKey(permission.name)) {
      return predefinedPermissionAppliesToRequest(permission, context);
    } else {
      return customPermissionAppliesToRequest(permission, context);
    }
  }

  private boolean predefinedPermissionAppliesToRequest(
      Permission predefinedPermission, AuthorizationContext context) {
    log.trace("Permission [{}] is a predefined perm", predefinedPermission);
    if (predefinedPermission.wellknownName == PermissionNameProvider.Name.ALL) {
      log.trace("'ALL' perm applies to all requests; perm applies.");
      return true; // 'ALL' applies to everything!
    } else if (!(context.getHandler() instanceof PermissionNameProvider handler)) {
      // TODO: Is this code path needed anymore, now that all handlers implement the interface?
      // context.getHandler returns Object and is not documented
      if (log.isTraceEnabled()) {
        log.trace(
            "Request handler [{}] is not a PermissionNameProvider, perm doesnt apply",
            context.getHandler());
      }
      // We're not 'ALL', and the handler isn't associated with any other predefined permissions
      return false;
    } else {
      PermissionNameProvider.Name permissionName = handler.getPermissionName(context);

      boolean applies =
          permissionName != null && predefinedPermission.name.equals(permissionName.name);
      log.trace(
          "Request handler [{}] is associated with predefined perm [{}]? {}",
          handler,
          predefinedPermission.name,
          applies);
      return applies;
    }
  }

  private boolean customPermissionAppliesToRequest(
      Permission customPermission, AuthorizationContext context) {
    log.trace("Permission [{}] is a custom permission", customPermission);
    if (customPermission.method != null
        && !customPermission.method.contains(context.getHttpMethod())) {
      if (log.isTraceEnabled()) {
        log.trace(
            "Custom permission requires method [{}] but request had method [{}]; permission doesn't apply",
            customPermission.method,
            context.getHttpMethod());
      }
      // this permissions HTTP method does not match this rule. try other rules
      return false;
    }
    if (customPermission.params != null) {
      for (Map.Entry<String, Function<String[], Boolean>> e : customPermission.params.entrySet()) {
        String[] paramVal = context.getParams().getParams(e.getKey());
        if (!e.getValue().apply(paramVal)) {
          if (log.isTraceEnabled()) {
            log.trace(
                "Request has param [{}] which is incompatible with custom perm [{}]; perm doesnt apply",
                e.getKey(),
                customPermission);
          }
          return false;
        }
      }
    }

    log.trace(
        "Perm [{}] matches method and params for request; permission applies", customPermission);
    return true;
  }

  private MatchStatus determineIfPermissionPermitsPrincipal(
      AuthorizationContext context, Permission governingPermission) {
    if (governingPermission.role == null) {
      log.debug("Governing permission [{}] has no role; permitting access", governingPermission);
      return MatchStatus.PERMITTED;
    }
    Principal principal = context.getUserPrincipal();
    if (principal == null) {
      log.debug(
          "Governing permission [{}] has role, but request principal cannot be identified; forbidding access",
          governingPermission);
      return MatchStatus.USER_REQUIRED;
    } else if (governingPermission.role.contains("*")) {
      log.debug(
          "Governing permission [{}] allows all roles; permitting access", governingPermission);
      return MatchStatus.PERMITTED;
    }

    Set<String> userRoles = getUserRoles(context);
    for (String role : governingPermission.role) {
      if (userRoles != null && userRoles.contains(role)) {
        log.debug(
            "Governing permission [{}] allows access to role [{}]; permitting access",
            governingPermission,
            role);
        return MatchStatus.PERMITTED;
      }
    }
    log.info(
        "This resource is configured to have a permission {}, The principal {} does not have the right role ",
        governingPermission,
        principal);
    return MatchStatus.FORBIDDEN;
  }

  public boolean doesUserHavePermission(
      Principal principal, PermissionNameProvider.Name permission) {
    Set<String> roles = getUserRoles(principal);
    if (roles != null) {
      for (String role : roles) {
        if (mapping.get(null) == null) continue;
        List<Permission> permissions = mapping.get(null).get(null);
        if (permissions != null) {
          for (Permission p : permissions) {
            if (permission.equals(p.wellknownName) && p.role.contains(role)) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  @Override
  @SuppressWarnings({"unchecked"})
  public void init(Map<String, Object> initInfo) {
    mapping.put(null, new WildCardSupportMap());
    List<Map<?, ?>> perms = getListValue(initInfo, "permissions");
    for (Map<?, ?> o : perms) {
      Permission p;
      try {
        p = Permission.load(o);
      } catch (Exception exp) {
        log.error("Invalid permission ", exp);
        continue;
      }
      add2Mapping(p);
    }
  }

  // this is to do optimized lookup of permissions for a given collection/path
  private void add2Mapping(Permission permission) {
    for (String c : permission.collections) {
      WildCardSupportMap m = mapping.computeIfAbsent(c, k -> new WildCardSupportMap());
      for (String path : permission.path) {
        List<Permission> perms = m.get(path);
        if (perms == null) m.put(path, perms = new ArrayList<>());
        perms.add(permission);
      }
    }
    Collection<String> roles =
        permission.role != null ? permission.role : Collections.singletonList(null);
    for (String r : roles) {
      Set<Permission> rm = roleToPermissionsMap.computeIfAbsent(r, k -> new HashSet<>());
      rm.add(permission);
    }
  }

  /**
   * Finds user roles
   *
   * @param context the authorization context to load roles from
   * @return set of roles as strings or empty set if no roles are found
   */
  public Set<String> getUserRoles(AuthorizationContext context) {
    return getUserRoles(context.getUserPrincipal());
  }

  /**
   * Finds users roles
   *
   * @param principal the user Principal to fetch roles for
   * @return set of roles as strings or empty set if no roles found
   */
  public abstract Set<String> getUserRoles(Principal principal);

  @Override
  public void close() throws IOException {}

  @SuppressWarnings("ImmutableEnumChecker")
  enum MatchStatus {
    USER_REQUIRED(AuthorizationResponse.PROMPT),
    NO_PERMISSIONS_FOUND(AuthorizationResponse.OK),
    PERMITTED(AuthorizationResponse.OK),
    FORBIDDEN(AuthorizationResponse.FORBIDDEN);

    final AuthorizationResponse rsp;

    MatchStatus(AuthorizationResponse rsp) {
      this.rsp = rsp;
    }
  }

  @Override
  public Map<String, Object> edit(Map<String, Object> latestConf, List<CommandOperation> commands) {
    for (CommandOperation op : commands) {
      AutorizationEditOperation operation = ops.get(op.name);
      if (operation == null) {
        op.unknownOperation();
        return null;
      }
      latestConf = operation.edit(latestConf, op);
      if (latestConf == null) return null;
    }
    return latestConf;
  }

  private static final Map<String, AutorizationEditOperation> ops =
      Arrays.stream(AutorizationEditOperation.values())
          .collect(toMap(AutorizationEditOperation::getOperationName, identity()));

  @Override
  public ValidatingJsonMap getSpec() {
    final List<Api> apis = AnnotatedApi.getApis(new ModifyRuleBasedAuthConfigAPI());
    return apis.get(0).getSpec();
  }
}
