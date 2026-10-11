package escuelaing.edu.co.truckdar.load_allocation.controller;

import escuelaing.edu.co.truckdar.load_allocation.dto.request.AssignDriverRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.request.CreateFreightLoadRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckdar.load_allocation.model.TruckTypeRequired;
import escuelaing.edu.co.truckdar.load_allocation.service.IFreightLoadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/loads")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class FreightLoadController {

    private final IFreightLoadService service;

    @PostMapping
    public ResponseEntity<FreightLoadResponse> publishLoad(@Valid @RequestBody CreateFreightLoadRequest request) {
        return new ResponseEntity<>(service.publishLoad(request), HttpStatus.CREATED);
    }

    @PatchMapping("/{loadId}/assign")
    public ResponseEntity<FreightLoadResponse> assignDriver(
            @PathVariable Long loadId,
            @Valid @RequestBody AssignDriverRequest request) {
        return ResponseEntity.ok(service.assignDriver(loadId, request));
    }

    @PatchMapping("/{loadId}/assignment/notify")
    public ResponseEntity<FreightLoadResponse> notifyAssignment(@PathVariable Long loadId) {
        return ResponseEntity.ok(service.notifyAssignment(loadId));
    }

    @PatchMapping("/{loadId}/assignment/accept")
    public ResponseEntity<FreightLoadResponse> acceptAssignment(@PathVariable Long loadId) {
        return ResponseEntity.ok(service.acceptAssignment(loadId));
    }

    @PatchMapping("/{loadId}/assignment/reject")
    public ResponseEntity<FreightLoadResponse> rejectAssignment(@PathVariable Long loadId) {
        return ResponseEntity.ok(service.rejectAssignment(loadId));
    }

    @PatchMapping("/{loadId}/assignment/timeout")
    public ResponseEntity<FreightLoadResponse> timeoutAssignment(@PathVariable Long loadId) {
        return ResponseEntity.ok(service.timeoutAssignment(loadId));
    }

    @GetMapping("/{loadId}")
    public ResponseEntity<FreightLoadResponse> getLoadById(@PathVariable Long loadId) {
        return ResponseEntity.ok(service.getLoadById(loadId));
    }

    @GetMapping("/available")
    public ResponseEntity<List<FreightLoadResponse>> getAvailableLoads(
            @RequestParam(required = false) TruckTypeRequired truckType) {
        return ResponseEntity.ok(service.getAvailableLoads(truckType));
    }

    @GetMapping("/company/{companyId}")
    public ResponseEntity<List<FreightLoadResponse>> getLoadsByCompany(@PathVariable String companyId) {
        return ResponseEntity.ok(service.getLoadsByCompany(companyId));
    }
}