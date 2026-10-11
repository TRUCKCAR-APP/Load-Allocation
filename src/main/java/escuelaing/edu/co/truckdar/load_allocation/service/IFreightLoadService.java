package escuelaing.edu.co.truckdar.load_allocation.service;

import escuelaing.edu.co.truckdar.load_allocation.dto.request.AssignDriverRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.request.CreateFreightLoadRequest;
import escuelaing.edu.co.truckdar.load_allocation.dto.response.FreightLoadResponse;
import escuelaing.edu.co.truckdar.load_allocation.model.TruckTypeRequired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface IFreightLoadService {
    FreightLoadResponse publishLoad(CreateFreightLoadRequest request, UUID correlationId);
    FreightLoadResponse assignDriver(Long loadId, AssignDriverRequest request);
    FreightLoadResponse notifyAssignment(Long loadId);
    boolean applyAssignmentNotified(Long loadId, String notifiedDriverId);
    boolean proposeReassignment(Long loadId, String driverId, String driverName, String vehiclePlate);
    boolean markNoDriversAvailable(Long loadId);
    boolean expireIfUnanswered(Long loadId, LocalDateTime notifiedBefore);
    FreightLoadResponse acceptAssignment(Long loadId, String authenticatedUserId);
    FreightLoadResponse rejectAssignment(Long loadId, String authenticatedUserId);
    FreightLoadResponse timeoutAssignment(Long loadId);
    FreightLoadResponse getLoadById(Long loadId);
    List<FreightLoadResponse> getAvailableLoads(TruckTypeRequired truckType);
    List<FreightLoadResponse> getLoadsByCompany(String generatorCompanyId);
}