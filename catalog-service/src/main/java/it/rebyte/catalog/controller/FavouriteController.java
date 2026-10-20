package it.rebyte.catalog.controller;
import org.springframework.http.HttpStatus; import org.springframework.security.core.annotation.AuthenticationPrincipal; import org.springframework.security.oauth2.jwt.Jwt; import org.springframework.web.bind.annotation.*; import it.rebyte.catalog.dto.*; import it.rebyte.catalog.service.FavouriteService;
@RestController @RequestMapping("/favourites") public class FavouriteController {private final FavouriteService service; public FavouriteController(FavouriteService s){service=s;}
 @PutMapping("/{listingId}") @ResponseStatus(HttpStatus.CREATED) public FavouriteResponse add(@PathVariable String listingId,@AuthenticationPrincipal Jwt jwt){return FavouriteResponse.from(service.add(jwt.getSubject(),listingId));}
 @DeleteMapping("/{listingId}") @ResponseStatus(HttpStatus.NO_CONTENT) public void remove(@PathVariable String listingId,@AuthenticationPrincipal Jwt jwt){service.remove(jwt.getSubject(),listingId);}
 @GetMapping public FavouritePage list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size,@AuthenticationPrincipal Jwt jwt){return service.list(jwt.getSubject(),page,size);}}
